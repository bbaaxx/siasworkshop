"""Mock mod: plays the mod's MCP-server role for the S0 spike (D1/D2).

Speaks newline-delimited JSON-RPC on stdin/stdout (stdout is pure protocol — logs go to
stderr). Owns a small scripted world (scenario JSON): observation text + open tasks.
Mirrors the mod's safety boundary (D7): unknown tools and bad args are rejected with a
structured error the model side can read and recover from.
"""
from __future__ import annotations

import json
import sys

from catalog import S0_TOOLS, by_wire, check_args


class MockWorld:
    def __init__(self, scenario: dict):
        self.scenario = scenario
        self.claimed: dict[str, str] = {}   # key -> claimant
        self.said: list[dict] = []

    def open_tasks(self) -> list[dict]:
        return [t for t in self.scenario.get("open_tasks", [])
                if t["key"] not in self.claimed and t.get("claimable", True)]


def tool_descriptor(t) -> dict:
    from catalog import resolve
    return {"name": t.wire, "description": t.description, "inputSchema": resolve(t.inputs)}


def handle_call(world: MockWorld, name: str, args: dict) -> dict:
    tool = by_wire(name)
    if tool is None:
        return {"isError": True, "content": [{"type": "text", "text":
                json.dumps({"reject": f"unknown tool '{name}'"})}]}
    err = check_args(tool.inputs, args)
    if err:
        return {"isError": True, "content": [{"type": "text", "text":
                json.dumps({"reject": f"bad args for {name}: {err}"})}]}
    if name == "observe_self":
        result = {"observation": world.scenario.get("observation", "")}
    elif name == "observe_board":
        open_tasks = world.open_tasks()
        result = {"open_tasks": [f"{t['key']} — {t.get('detail', '')}" for t in open_tasks]} \
            if open_tasks else {"open_tasks": []}
    elif name == "claim":
        wanted = args.get("task")
        open_tasks = world.open_tasks()
        target = None
        if wanted:
            target = next((t for t in open_tasks if t["key"] == wanted), None)
            if target is None:
                return {"isError": True, "content": [{"type": "text", "text": json.dumps(
                    {"reject": f"unknown or claimed task key '{wanted}'",
                     "open": [t["key"] for t in open_tasks]})}]}
        elif open_tasks:
            target = open_tasks[0]  # mock priority: board order
        if target is None:
            return {"isError": True, "content": [{"type": "text", "text":
                    json.dumps({"reject": "no open tasks to claim"})}]}
        world.claimed[target["key"]] = "agent-1"
        result = {"claimed": target["key"]}
    elif name == "rest":
        result = {"resting": True}
    elif name == "say":
        world.said.append({"player": args["player"], "msg": args["msg"]})
        result = {"said": {"player": args["player"], "msg": args["msg"]}}
    else:  # pragma: no cover - by_wire covers the S0 set
        return {"isError": True, "content": [{"type": "text", "text":
                json.dumps({"reject": f"tool '{name}' not implemented by mock"})}]}
    return {"isError": False, "content": [{"type": "text", "text": json.dumps(result)}]}


def serve(scenario_path: str) -> None:
    with open(scenario_path, encoding="utf-8") as fh:
        scenario = json.load(fh)
    world = MockWorld(scenario)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        method, msg_id = msg.get("method"), msg.get("id")
        if method == "initialize":
            reply = {"jsonrpc": "2.0", "id": msg_id, "result": {
                "protocolVersion": msg.get("params", {}).get("protocolVersion", "2025-06-18"),
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "siasworkshop-mockmod", "version": "0.1.0"}}}
        elif method == "notifications/initialized":
            continue
        elif method == "tools/list":
            reply = {"jsonrpc": "2.0", "id": msg_id,
                     "result": {"tools": [tool_descriptor(t) for t in S0_TOOLS]}}
        elif method == "tools/call":
            params = msg.get("params", {})
            result = handle_call(world, params.get("name", ""), params.get("arguments") or {})
            reply = {"jsonrpc": "2.0", "id": msg_id, "result": result}
        else:
            reply = {"jsonrpc": "2.0", "id": msg_id,
                     "error": {"code": -32601, "message": f"method not found: {method}"}}
        sys.stdout.write(json.dumps(reply) + "\n")
        sys.stdout.flush()
        if method == "tools/call" and world.said:
            sys.stderr.write(f"[mockmod] say: {world.said[-1]}\n")
            sys.stderr.flush()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.stderr.write("usage: mockmod.py <scenario.json>\n")
        sys.exit(2)
    serve(sys.argv[1])
