#!/usr/bin/env python3
"""Hot-plug MCP probe: attach to the mod's debug transport (agents.mcp.debugPort) and read
the live agent surface. Stdlib only.

Requires the mod running with agents.mcp.enabled=true and agents.mcp.debugPort>0; the
listener is 127.0.0.1-only and serves a READ-ONLY catalog (observe_self/observe_board).

Usage:
  python3 tools/mcp_probe.py --port 25575 list
  python3 tools/mcp_probe.py --port 25575 board
  python3 tools/mcp_probe.py --port 25575 self --agent <uuid>

Companion to the P1 wire tap (sidecar.py --tap): the tap shows what the MODEL saw on the
sidecar channel; this probe shows what a debug client can see on the attach channel.
"""
from __future__ import annotations

import argparse
import itertools
import json
import socket
import sys


class Probe:
    """Minimal newline-JSON-RPC client for the debug server role (we drive, the mod answers)."""

    def __init__(self, port: int, host: str = "127.0.0.1", timeout: float = 15.0):
        self._socket = socket.create_connection((host, port), timeout=timeout)
        self._fh = self._socket.makefile("rw", encoding="utf-8", newline="\n")
        self._ids = itertools.count(1)

    def request(self, method: str, params: dict | None = None) -> dict:
        msg_id = next(self._ids)
        msg = {"jsonrpc": "2.0", "id": msg_id, "method": method}
        if params is not None:
            msg["params"] = params
        self._fh.write(json.dumps(msg) + "\n")
        self._fh.flush()
        for line in self._fh:
            line = line.strip()
            if not line:
                continue
            reply = json.loads(line)
            if reply.get("id") == msg_id:
                if reply.get("error") is not None:
                    raise RuntimeError(f"{method} rejected: {reply['error']}")
                return reply.get("result") or {}
        raise RuntimeError("debug server closed the connection")

    def close(self):
        try:
            self._socket.close()
        except OSError:
            pass


def handshake(probe: Probe) -> dict:
    result = probe.request("initialize", {
        "protocolVersion": "2025-06-18",
        "capabilities": {"tools": {"listChanged": False}},
        "clientInfo": {"name": "mcp-probe", "version": "1.0"},
    })
    return result


def tool_body(result: dict) -> dict:
    content = result.get("content") or []
    text = "\n".join(c.get("text", "") for c in content if c.get("type") == "text")
    try:
        return json.loads(text) if text.strip() else {}
    except ValueError:
        return {"raw": text}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, required=True, help="agents.mcp.debugPort")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("command", choices=["list", "self", "board"])
    ap.add_argument("--agent", help="agent uuid (required for 'self')")
    args = ap.parse_args()

    probe = Probe(args.port, args.host)
    try:
        info = handshake(probe)
        print(f"connected: {info.get('serverInfo', {}).get('name', '?')} "
              f"({info.get('protocolVersion', '?')})")
        if args.command == "list":
            tools = probe.request("tools/list", {}).get("tools", [])
            for tool in tools:
                print(f"- {tool.get('name')}: {tool.get('description', '')}")
            return 0
        if args.command == "self":
            if not args.agent:
                print("error: 'self' requires --agent <uuid>", file=sys.stderr)
                return 2
            body = tool_body(probe.request("tools/call", {
                "name": "observe_self", "arguments": {"agent": args.agent}}))
        else:
            arguments = {"agent": args.agent} if args.agent else {}
            body = tool_body(probe.request("tools/call", {
                "name": "observe_board", "arguments": arguments}))
        if "reject" in body:
            print(f"rejected: {body['reject']}")
            return 1
        print(json.dumps(body, indent=2, ensure_ascii=False))
        return 0
    finally:
        probe.close()


if __name__ == "__main__":
    sys.exit(main())
