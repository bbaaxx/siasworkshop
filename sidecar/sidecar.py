"""Sia's Workshop MCP sidecar (S1 bridge) — the real child-role process the mod spawns.

Topology (reference/mcp-pattern-tree-design D1/D2): the mod is the MCP server and owns
the tool registry; THIS process is the MCP client + model host. It speaks newline-delimited
JSON-RPC over its own stdio: requests go to stdout (the mod reads them), responses arrive
on stdin. Single-threaded and strictly sequential: the mod only talks to us inside a
`decide` call, and our mod-tool requests happen inside that call, so a blocking stdin loop
cannot deadlock (the mod's server thread is never parked on us).

Flow: initialize handshake -> the mod may tools/list (we offer `decide`) -> mod calls
`decide(agent)` -> we pull observation/board via mod tools, ask the model (OpenAI-compatible
endpoint, or --replay scripted replies for offline tests), validate against the catalog,
execute claim/rest/say via mod tools, and answer with a small summary JSON.

Stdlib only. Never prints the API key.
"""
from __future__ import annotations

import argparse
import itertools
import json
import sys

from catalog import (S0_TOOLS, by_wire, check_args, first_json_object, render_native,
                     render_prompt)
from model import FakeModel, OpenAiChat, profile_for


class ModPeer:
    """JSON-RPC peer on our own stdio: send requests on stdout, read traffic on stdin."""

    def __init__(self):
        self._reader = sys.stdin.buffer
        self._writer = sys.stdout.buffer
        self._ids = itertools.count(1)
        self._pending: dict[int, dict] = {}

    def _send(self, msg: dict):
        self._writer.write(json.dumps(msg).encode() + b"\n")
        self._writer.flush()

    def request(self, method: str, params: dict | None = None) -> dict:
        msg_id = next(self._ids)
        msg = {"jsonrpc": "2.0", "id": msg_id, "method": method}
        if params is not None:
            msg["params"] = params
        self._send(msg)
        while True:
            reply = self.recv()
            if reply is None:
                raise RuntimeError("mod closed the pipe")
            if reply.get("id") == msg_id and (reply.get("result") is not None or reply.get("error") is not None):
                if reply.get("error") is not None:
                    raise RuntimeError(f"mod rejected {method}: {reply['error']}")
                return reply["result"]
            # anything else inbound here is unexpected mid-request; ignore defensively

    def respond(self, msg_id, result):
        self._send({"jsonrpc": "2.0", "id": msg_id, "result": result})

    def notify(self, method: str, params: dict | None = None):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        self._send(msg)

    def recv(self) -> dict | None:
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


def call_mod_tool(peer: ModPeer, name: str, args: dict) -> dict:
    result = peer.request("tools/call", {"name": name, "arguments": args})
    content = result.get("content") or []
    text = "\n".join(c.get("text", "") for c in content if c.get("type") == "text")
    parsed = None
    try:
        parsed = json.loads(text) if text.strip() else None
    except ValueError:
        parsed = None
    body = parsed if isinstance(parsed, dict) else {"raw": text}
    if result.get("isError"):
        raise RuntimeError(f"mod tool {name} rejected: {body}")
    return body


def decide(peer: ModPeer, model, model_tag: str, args: dict, mode: str,
           temperature: float, max_tokens: int) -> dict:
    agent = args.get("agent", "")
    obs = call_mod_tool(peer, "observe_self", {"agent": agent})
    board = call_mod_tool(peer, "observe_board", {"agent": agent})
    observation = obs.get("observation", "")
    open_lines = list(board.get("open_tasks") or [])
    open_keys = [line.split(" — ")[0] for line in open_lines]

    if mode == "native":
        messages = [
            {"role": "system", "content":
                "You control a Minecraft villager-agent. Observe the situation, then call exactly "
                "one tool to decide: claim a task, rest, or say something."},
            {"role": "user", "content": "OBSERVATION:\n" + observation
             + "\n\nOPEN TASKS:\n" + ("\n".join("- " + l for l in open_lines) or "none")},
        ]
        reply = model.chat(model_tag, messages, tools=render_native(),
                           temperature=temperature, max_tokens=max_tokens)
        if not reply.ok or not reply.tool_calls:
            return {"decision": "error", "detail": reply.error or "no_tool_call"}
        call = reply.tool_calls[0]
        tool, call_args = call["name"], call["args"]
    else:
        messages = [
            {"role": "system", "content": render_prompt()},
            {"role": "user", "content": "OBSERVATION:\n" + observation
             + "\n\nOPEN TASKS:\n" + ("\n".join("- " + l for l in open_lines) or "none")
             + '\n\nDecide: reply with exactly one JSON object {"tool":"<id>","args":{...}}.'},
        ]
        reply = model.chat(model_tag, messages, temperature=temperature, max_tokens=max_tokens)
        if not reply.ok:
            return {"decision": "error", "detail": reply.error or "unknown"}
        candidate = first_json_object(reply.content or "")
        if candidate is None or not isinstance(candidate.get("tool"), str):
            return {"decision": "error", "detail": "malformed_json"}
        tool = candidate["tool"]
        call_args = candidate.get("args") if isinstance(candidate.get("args"), dict) else {}

    catalog_tool = by_wire(tool)
    if catalog_tool is None:
        return {"decision": "error", "detail": f"unknown_tool:{tool}"}
    arg_err = check_args(catalog_tool.inputs, call_args)
    if arg_err:
        return {"decision": "error", "detail": f"bad_args:{tool}:{arg_err}"}

    exec_args = dict(call_args)
    if tool in ("claim", "rest", "say"):
        exec_args["agent"] = agent
    outcome = call_mod_tool(peer, tool, exec_args)
    if tool == "claim":
        return {"decision": "claim", "task": outcome.get("claimed", ""), "detail": json.dumps(outcome)}
    return {"decision": tool, "detail": json.dumps(outcome)}


def serve(peer: ModPeer, model, model_tag: str, mode: str, temperature: float, max_tokens: int) -> None:
    # client-role handshake: we initiate, the mod answers on our stdin
    peer.request("initialize", {
        "protocolVersion": "2025-06-18",
        "capabilities": {"tools": {"listChanged": False}},
        "clientInfo": {"name": "siasworkshop-sidecar", "version": "0.1.0"},
    })
    peer.notify("notifications/initialized")
    while True:
        msg = peer.recv()
        if msg is None:
            break
        if msg.get("method") == "tools/list" and msg.get("id") is not None:
            peer.respond(msg["id"], {"tools": [{
                "name": "decide",
                "description": "Run one decision cycle for an agent: observe, ask the model, "
                               "execute claim/rest/say. Args: agent=<uuid>.",
                "inputSchema": {"type": "object",
                                "properties": {"agent": {"type": "string"}},
                                "required": ["agent"]}}]})
        elif msg.get("method") == "tools/call" and msg.get("id") is not None:
            params = msg.get("params", {})
            name = params.get("name", "")
            args = params.get("arguments") or {}
            if name != "decide":
                peer.respond(msg["id"], {"isError": True, "content": [{"type": "text", "text":
                    json.dumps({"reject": f"unknown tool '{name}'"})}]})
                continue
            try:
                summary = decide(peer, model, model_tag, args, mode, temperature, max_tokens)
                peer.respond(msg["id"], {"isError": False, "content": [{"type": "text", "text":
                    json.dumps(summary)}]})
            except RuntimeError as e:
                peer.respond(msg["id"], {"isError": True, "content": [{"type": "text", "text":
                    json.dumps({"reject": str(e)})}]})
        # responses to our own requests are consumed inside ModPeer.request


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--endpoint", default="http://127.0.0.1:1234/v1")
    ap.add_argument("--model", default="prism-ml/bonsai-27b")
    ap.add_argument("--api-key-env", default="OPENROUTER_API_KEY")
    ap.add_argument("--mode", choices=["prompt", "native"], default=None,
                    help="model-facing surface (default: the model's profile)")
    ap.add_argument("--temperature", type=float, default=None)
    ap.add_argument("--max-tokens", type=int, default=None)
    ap.add_argument("--replay", help="scripted replies JSON (offline tests; no network)")
    args = ap.parse_args()

    profile = profile_for(args.model)
    mode = args.mode or ("native" if profile["mode"] == "native" else "prompt")
    temperature = args.temperature if args.temperature is not None else profile.get("temperature", 0.3)
    max_tokens = args.max_tokens or profile.get("max_tokens", 1024)

    if args.replay:
        with open(args.replay, encoding="utf-8") as fh:
            script = json.load(fh)
        model = FakeModel(script, {})
        model_tag = mode
    else:
        import os
        api_key = os.environ.get(args.api_key_env) or None
        model = OpenAiChat(args.endpoint, api_key, timeout_seconds=180)
        model_tag = args.model

    serve(ModPeer(), model, model_tag, mode, temperature, max_tokens)
    return 0


if __name__ == "__main__":
    sys.exit(main())
