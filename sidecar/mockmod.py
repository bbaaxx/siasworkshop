"""Mock mod: plays the mod's MCP-server role for the S0/S2 spike (D1/D2).

Speaks newline-delimited JSON-RPC on stdin/stdout (stdout is pure protocol — logs go to
stderr). Owns a small scripted world (scenario JSON): observation text, open tasks, and
for the building walk (S2): candidate sites, build cards, storage, blueprint store, plus
a build task that converges over observe_task polls (simulated ticks).

Mirrors the mod's safety boundary (D7): unknown tools, bad args, out-of-order pattern
calls, not-yet-created blueprints, and claims on cooling-down (claimable:false) tasks
are rejected with structured errors the model side can read and recover from. Pattern
results are D5 cards: {"result": ..., "children": [...]}.
"""
from __future__ import annotations

import json
import sys

from catalog import (BUILDING_WALK_TOOLS, S0_TOOLS, WALK_CHILDREN, by_wire, check_args)
from catalog import Tool, resolve

OBSERVE_TASK = Tool(id="observe.task", kind="observe",
                    description="Read-only: one board task's status and progress; polling a build "
                                "task advances the world one tick.",
                    inputs={"type": "object",
                            "properties": {"task": {"type": "string"}},
                            "required": ["task"]},
                    args_hint="task=<task id or key>")


class MockWorld:
    def __init__(self, scenario: dict):
        self.scenario = scenario
        self.claimed: dict[str, str] = {}      # key -> claimant
        self.said: list[dict] = []
        self.blueprints: dict[str, dict] = {}  # blueprint id -> {design, anchor, total}
        self.walk_tasks: dict[str, dict] = {}  # key -> {status, done, total}
        self._walk_seq = 0
        self.intent: dict | None = None

    def open_tasks(self) -> list[dict]:
        # claimable:false tasks stay listed (cooldown is claim-time, not board-time —
        # "listed but not claimable"); claim rejects them below.
        tasks = [t for t in self.scenario.get("open_tasks", [])
                 if t["key"] not in self.claimed]
        for key, job in self.walk_tasks.items():
            if job["status"] in ("OPEN", "CLAIMED"):
                tasks.append({"key": key, "type": "BUILD",
                              "detail": f"walk build {job['done']}/{job['total']} placed"})
        return tasks

    # --- building walk helpers -------------------------------------------------

    def cards(self) -> list[dict]:
        return self.scenario.get("cards", [])

    def card(self, name: str) -> dict | None:
        return next((c for c in self.cards() if c["name"] == name), None)

    def sites_ranked(self) -> list[dict]:
        sites = list(self.scenario.get("sites", []))
        sites.sort(key=lambda s: (not s.get("flat", True), s.get("chestDist", 99)))
        return sites

    def advance(self, job: dict):
        job["done"] = min(job["total"], job["done"] + max(1, job["total"] // 4))
        if job["done"] >= job["total"]:
            job["status"] = "DONE"


def card_children(wire_name: str) -> list[dict]:
    ids = {t.wire: t.id for t in BUILDING_WALK_TOOLS}
    dotted = ids.get(wire_name)
    child_ids = WALK_CHILDREN.get(dotted, []) if dotted else []
    children = []
    for child_id in child_ids:
        child = next((t for t in BUILDING_WALK_TOOLS if t.id == child_id), None)
        if child:
            children.append({"name": child.wire, "description": child.description,
                             "inputs": resolve(child.inputs)})
    return children


def _err(message: dict) -> dict:
    return {"isError": True, "content": [{"type": "text", "text": json.dumps(message)}]}


def _ok(result: dict, children: list[dict] | None = None) -> dict:
    # patterns answer as D5 cards {"result", "children"}; effects/observe answers are
    # plain top-level results
    body = {"result": result, "children": children} if children is not None else result
    return {"isError": False, "content": [{"type": "text", "text": json.dumps(body)}]}


def handle_walk_call(world: MockWorld, wire_name: str, args: dict) -> dict | None:
    """Building-walk handlers; returns None when wire_name is not a walk tool."""
    if wire_name == "observe_task":
        key = args.get("task", "")
        job = world.walk_tasks.get(key)
        if job is None:
            board = next((t for t in world.scenario.get("open_tasks", [])
                          if t["key"] == key), None)
            if board is None:
                return _err({"reject": f"unknown task '{key}'"})
            return _ok({"task": key, "status": "OPEN"})
        world.advance(job)
        return _ok({"task": key, "status": job["status"],
                    "progress": f"{job['done']}/{job['total']}"})

    tool = next((t for t in BUILDING_WALK_TOOLS if t.wire == wire_name), None)
    if tool is None:
        return None
    err = check_args(tool.inputs, args)
    if err:
        return _err({"reject": f"bad args for {wire_name}: {err}"})

    if wire_name == "building_think_through_request":
        request = args["request"].strip()
        if len(request) < 3:
            return _err({"reject": "request too vague (min 3 chars)"})
        world.intent = {"request": request, "sizeHint": "small"}
        return _ok({"designIntent": world.intent,
                    "note": "intent set — analyze a site or pick a design next"},
                   card_children(wire_name))

    if wire_name == "building_analyze_terrain":
        sites = world.sites_ranked()
        if not sites:
            return _err({"reject": "no candidate sites in this scenario"})
        near = args["near"]
        ranked = [{"site": s["pos"], "flat": s.get("flat", True),
                   "chestDist": s.get("chestDist"), "note": s.get("note", "")}
                  for s in sites]
        return _ok({"near": near, "sites": ranked},
                   card_children(wire_name))

    if wire_name == "building_design":
        style = args.get("style", "").lower()
        options = []
        for card in world.cards():
            tags = [t.lower() for t in card.get("styleTags", [])]
            if style and style not in tags:
                continue
            options.append({"name": card["name"], "size": card["size"],
                            "steps": card["stepSummary"], "styleTags": card["styleTags"],
                            "palette": card["palette"]})
        if not options:
            return _err({"reject": f"no designs match style '{style}'",
                         "available": [c["name"] for c in world.cards()]})
        return _ok({"site": args["site"], "options": options},
                   card_children(wire_name))

    if wire_name == "building_select_materials":
        card = world.card(args["design"])
        if card is None:
            return _err({"reject": f"unknown design '{args['design']}'",
                         "available": [c["name"] for c in world.cards()]})
        available = world.scenario.get("materials", {})
        needed = card["palette"]
        have = {k: min(v, available.get(k, 0)) for k, v in needed.items()}
        missing = {k: v - have[k] for k, v in needed.items() if have[k] < v}
        return _ok({"design": card["name"], "needed": needed, "have": have,
                    "missing": missing, "affordable": not missing},
                   card_children(wire_name))

    if wire_name == "building_create_blueprint":
        card = world.card(args["design"])
        if card is None:
            return _err({"reject": f"unknown design '{args['design']}'",
                         "available": [c["name"] for c in world.cards()]})
        anchor = args["anchor"]
        blueprint_id = f"bp:{card['name']}@{anchor['x']},{anchor['y']},{anchor['z']}"
        total = sum(card["palette"].values())
        world.blueprints[blueprint_id] = {"design": card["name"], "anchor": anchor,
                                          "total": total}
        return _ok({"blueprint": blueprint_id, "placements": total},
                   card_children(wire_name))

    if wire_name == "building_apply_blueprint":
        blueprint_id = args["blueprint"]
        job = world.blueprints.get(blueprint_id)
        if job is None:
            return _err({"reject": f"unknown blueprint '{blueprint_id}' — run create_blueprint first",
                         "blueprints": sorted(world.blueprints)})
        anchor = args["anchor"]
        if anchor != job["anchor"]:
            return _err({"reject": f"anchor mismatch: blueprint was created at {job['anchor']}"})
        world._walk_seq += 1
        key = f"build:walk:{world._walk_seq:02d}"
        world.walk_tasks[key] = {"status": "CLAIMED", "done": 0, "total": job["total"]}
        return _ok({"claimed": key, "blueprint": blueprint_id,
                    "convergence": "poll observe_task until DONE"})

    return _err({"reject": f"tool '{wire_name}' not implemented by mock"})  # pragma: no cover


def handle_call(world: MockWorld, name: str, args: dict) -> dict:
    walk = handle_walk_call(world, name, args)
    if walk is not None:
        return walk
    tool = by_wire(name)
    if tool is None and name == "observe_task":
        tool = OBSERVE_TASK
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
        if not target.get("claimable", True):
            return _err({"reject": "cooling down after a recent release"})
        world.claimed[target["key"]] = "agent-1"
        result = {"claimed": target["key"]}
    elif name == "rest":
        result = {"resting": True}
    elif name == "say":
        world.said.append({"player": args["player"], "msg": args["msg"]})
        result = {"said": {"player": args["player"], "msg": args["msg"]}}
    else:
        return {"isError": True, "content": [{"type": "text", "text":
                json.dumps({"reject": f"tool '{name}' not implemented by mock"})}]}
    return {"isError": False, "content": [{"type": "text", "text": json.dumps(result)}]}


def tool_descriptor(t) -> dict:
    return {"name": t.wire, "description": t.description, "inputSchema": resolve(t.inputs)}


def serve(scenario_path: str) -> None:
    with open(scenario_path, encoding="utf-8") as fh:
        scenario = json.load(fh)
    world = MockWorld(scenario)
    catalog = list(S0_TOOLS)
    if scenario.get("sites"):
        catalog = catalog + [OBSERVE_TASK] + BUILDING_WALK_TOOLS
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
                "serverInfo": {"name": "siasworkshop-mockmod", "version": "0.2.0"}}}
        elif method == "notifications/initialized":
            continue
        elif method == "tools/list":
            reply = {"jsonrpc": "2.0", "id": msg_id,
                     "result": {"tools": [tool_descriptor(t) for t in catalog]}}
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
