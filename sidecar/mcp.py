"""MCP-over-stdio client (sidecar role, D2).

Framing: newline-delimited JSON-RPC, exactly as the mod will implement it on the Java side.
The client writes requests to the peer's stdin and reads responses from the peer's stdout.
In production the mod spawns the sidecar and these are the sidecar's own pipes, reversed;
here the spike spawns the mock mod and drives the same wire from the parent.
"""
from __future__ import annotations

import itertools
import json
import select
import subprocess
import sys


class JsonRpcPipe:
    def __init__(self, reader, writer):
        self._reader = reader          # binary readable (peer's stdout)
        self._writer = writer          # binary writable (peer's stdin)
        self._ids = itertools.count(1)

    def send(self, method: str | None, params: dict | None = None,
             is_notification: bool = False) -> int | None:
        msg = {"jsonrpc": "2.0"}
        msg_id = None
        if not is_notification:
            msg_id = next(self._ids)
            msg["id"] = msg_id
        if method is not None:
            msg["method"] = method
        if params is not None:
            msg["params"] = params
        self._writer.write(json.dumps(msg).encode() + b"\n")
        self._writer.flush()
        return msg_id

    def recv(self, timeout_s: float | None = None) -> dict | None:
        """Next JSON-RPC message, or None on timeout/EOF. Server->client traffic only here."""
        ready, _, _ = select.select([self._reader], [], [], timeout_s)
        if not ready:
            return None
        line = self._reader.readline()
        if not line:
            return None
        line = line.strip()
        if not line:
            return {}
        try:
            return json.loads(line.decode())
        except ValueError:
            return {}


PROTOCOL_VERSION = "2025-06-18"


class McpClient:
    """Minimal MCP client: initialize handshake, tools/list, tools/call."""

    def __init__(self, proc: subprocess.Popen):
        self._proc = proc
        self._pipe = JsonRpcPipe(proc.stdout, proc.stdin)

    def start(self) -> dict:
        self._pipe.send("initialize", {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "siasworkshop-sidecar-s0", "version": "0.1.0"},
        })
        while True:
            msg = self._pipe.recv(timeout_s=30)
            if msg is None:
                raise RuntimeError("mock mod did not answer initialize (EOF/timeout)")
            if msg.get("id") == 1 and "result" in msg:
                result = msg["result"]
                break
            if "error" in msg:
                raise RuntimeError(f"initialize rejected: {msg['error']}")
        self._pipe.send("notifications/initialized", is_notification=True)
        return result

    def list_tools(self) -> list[dict]:
        msg_id = self._pipe.send("tools/list")
        reply = self._wait_result(msg_id)
        return reply["result"]["tools"]

    def call(self, name: str, args: dict | None = None, timeout_s: float = 30) -> dict:
        """tools/call; returns {"ok": bool, "content": parsed-or-text, "is_error": bool}."""
        msg_id = self._pipe.send("tools/call", {"name": name, "arguments": args or {}})
        reply = self._wait_result(msg_id, timeout_s)
        result = reply["result"]
        content = result.get("content") or []
        text = "\n".join(c.get("text", "") for c in content if c.get("type") == "text")
        parsed = None
        try:
            parsed = json.loads(text) if text.strip() else None
        except ValueError:
            parsed = None
        return {"ok": not result.get("isError", False), "content": parsed if parsed is not None else text,
                "is_error": result.get("isError", False), "raw": text}

    def _wait_result(self, msg_id: int, timeout_s: float = 30) -> dict:
        while True:
            msg = self._pipe.recv(timeout_s=timeout_s)
            if msg is None:
                raise RuntimeError(f"no reply to request {msg_id} (EOF/timeout)")
            if msg.get("method"):          # notification (e.g. tools/list_changed): ignore
                continue
            if msg.get("id") == msg_id:
                if "error" in msg:
                    raise RuntimeError(f"request {msg_id} failed: {msg['error']}")
                return msg

    def close(self):
        try:
            if self._proc.stdin:
                self._proc.stdin.close()
        except BrokenPipeError:
            pass
        try:
            self._proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self._proc.kill()


def spawn_mock(scenario_path: str, python: str = sys.executable) -> McpClient:
    import os.path
    mock = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mockmod.py")
    proc = subprocess.Popen([python, mock, scenario_path],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=None)
    return McpClient(proc)
