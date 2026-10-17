"""Tool catalog: descriptors, schema-subset validation, and model-facing renderers.

Implements the descriptor format from mdocs/wiki/reference/mcp-pattern-tree-design.md
(D4/D5). Wire names use underscores (MCP tool names must match ^[a-zA-Z0-9_-]{1,64}$);
dotted ids remain the internal/catalog alias. Stdlib only.
"""
from __future__ import annotations

import json
from dataclasses import dataclass

POS_REF = "#types/pos"


# --- schema subset -----------------------------------------------------------

def check_value(schema: dict, value) -> str | None:
    """Validate one value against the schema subset; returns an error string or None."""
    ref = schema.get("$ref")
    if ref is not None:
        if ref == POS_REF:
            if not (isinstance(value, dict)
                    and all(isinstance(value.get(k), int) and not isinstance(value.get(k), bool)
                            for k in ("x", "y", "z"))):
                return "expected pos {x,y,z} ints"
            return None
        return f"unknown $ref {ref}"
    t = schema.get("type")
    if t == "string" and not isinstance(value, str):
        return "expected string"
    if t == "number" and not (isinstance(value, (int, float)) and not isinstance(value, bool)):
        return "expected number"
    if t == "integer" and not (isinstance(value, int) and not isinstance(value, bool)):
        return "expected integer"
    if t == "boolean" and not isinstance(value, bool):
        return "expected boolean"
    if t == "array":
        if not isinstance(value, list):
            return "expected array"
        for item in value:
            err = check_value(schema.get("items", {}), item)
            if err:
                return f"array item: {err}"
    enum = schema.get("enum")
    if enum is not None and value not in enum:
        return f"expected one of {enum}"
    return None


def check_args(inputs: dict, args) -> str | None:
    """Validate a tool-call argument object against a descriptor's inputs; error or None."""
    if not isinstance(args, dict):
        return "args is not a JSON object"
    for req in inputs.get("required", []):
        if req not in args:
            return f"missing required arg '{req}'"
    props = inputs.get("properties", {})
    for key, val in args.items():
        if key not in props:
            return f"unknown arg '{key}'"
        err = check_value(props[key], val)
        if err:
            return f"arg '{key}': {err}"
    return None


def resolve(schema: dict) -> dict:
    """Inline #types/pos refs so the schema is a standalone JSON Schema (native tool rendering)."""
    out = {}
    for key, val in schema.items():
        if key == "$ref" and val == POS_REF:
            return {"type": "object",
                    "properties": {k: {"type": "integer"} for k in ("x", "y", "z")},
                    "required": ["x", "y", "z"],
                    "additionalProperties": False}
        if isinstance(val, dict):
            out[key] = resolve(val)
        else:
            out[key] = val
    return out


# --- descriptors -------------------------------------------------------------

@dataclass(frozen=True)
class Tool:
    id: str                 # dotted internal id, e.g. "observe.self"
    kind: str               # observe | pattern | effect
    description: str
    inputs: dict            # schema subset
    args_hint: str = ""     # compact arg spelling for prompt rendering, e.g. "task=<key>"

    @property
    def wire(self) -> str:
        return self.id.replace(".", "_")

    @classmethod
    def from_dict(cls, d: dict) -> "Tool":
        return cls(id=d["id"], kind=d["kind"], description=d.get("description", ""),
                   inputs=d.get("inputs", {"type": "object", "properties": {}}),
                   args_hint=d.get("argsHint", ""))


def catalog_name(wire_name: str) -> str:
    return wire_name.replace("_", ".")


# The S0 parity surface: the v1 three decision actions + two observation tools.
S0_TOOLS: list[Tool] = [
    Tool(id="observe.self", kind="observe",
         description="Read-only: this agent's compact situation snapshot (self, players, nearby blocks/entities, environment).",
         inputs={"type": "object", "properties": {}}),
    Tool(id="observe.board", kind="observe",
         description="Read-only: the shared task board — open tasks with their exact claim keys.",
         inputs={"type": "object", "properties": {}}),
    Tool(id="claim", kind="effect",
         description="Start work on an OPEN TASK from the board. Once claimed, the agent's hands "
                     "(walk, place, light, build) run automatically in the mod.",
         inputs={"type": "object",
                 "properties": {"task": {"type": "string"}},
                 "required": []},
         args_hint="task=<exact key from the board> (optional; omitted = highest-priority open task)"),
    Tool(id="rest", kind="effect",
         description="Deliberately idle this cycle.",
         inputs={"type": "object", "properties": {}}, args_hint=""),
    Tool(id="say", kind="effect",
         description="Speak one short line to a player (shows up in their chat).",
         inputs={"type": "object",
                 "properties": {"player": {"type": "string"}, "msg": {"type": "string"}},
                 "required": ["player", "msg"]},
         args_hint="player=<name>, msg=<short text>"),
]


def by_wire(name: str) -> Tool | None:
    for tool in S0_TOOLS:
        if tool.wire == name:
            return tool
    return None


# --- renderers ----------------------------------------------------------------

def render_native(tools: list[Tool] | None = None) -> list[dict]:
    """OpenAI/MCP-native tools array (function calling)."""
    return [{"type": "function",
             "function": {"name": t.wire,
                          "description": t.description,
                          "parameters": resolve(t.inputs)}}
            for t in (tools or S0_TOOLS)]


def render_prompt(tools: list[Tool] | None = None) -> str:
    """Prompt-mode tool block: the catalog rendered as text with a constrained-JSON contract."""
    lines = ["You control a Minecraft villager-agent through TOOLS. "
             "Think briefly, then answer with exactly one JSON object: {\"tool\":\"<id>\",\"args\":{...}}. "
             "No code fences, no prose."]
    for t in (tools or S0_TOOLS):
        hint = f" args: {t.args_hint}" if t.args_hint else ""
        lines.append(f"- {t.wire} — {t.description}{hint}")
    return "\n".join(lines)


# --- v1 prompt replica (A/B parity baseline) ----------------------------------

V1_SYSTEM = ("You pick ONE action for a Minecraft villager-agent. Think briefly, then answer with "
             "one JSON object only: {\"action\":\"...\",\"args\":{...}}. No code fences, no prose.")


def render_v1_user(observation: str, open_task_lines: list[str]) -> str:
    """Faithful replica of LlmPrompts.userPrompt with empty pool/build-cards (the S0 decision space)."""
    sb = ["SITUATION:\n" + observation, "\nOPEN TASKS:"]
    if not open_task_lines:
        sb.append(' none — reply {"action":"rest","args":{}}.')
    else:
        for line in open_task_lines:
            sb.append("\n- " + line)
    sb.append("\n\nDECISION — reply with exactly one JSON object:")
    sb.append('\n- Start work on an OPEN TASK: {"action":"claim","args":{"task":"<key>"}} (copy the key exactly)')
    sb.append('\n- Idle: {"action":"rest","args":{}}')
    sb.append('\n- Speak to a player: {"action":"say","args":{"player":"<name>","msg":"<short text>"}}')
    sb.append("\nClaiming is the only way to act: once a task is claimed, the agent's hands"
              " (walk, place, light, build) run automatically. Any other action is ignored.")
    return "".join(sb)


def first_json_object(text: str) -> dict | None:
    """First {...} span parsed (fence/prose tolerant); mirrors LlmActions.parseJsonObject."""
    if text is None:
        return None
    trimmed = text.strip()
    if not trimmed:
        return None
    for candidate in (trimmed,):
        try:
            val = json.loads(candidate)
            if isinstance(val, dict):
                return val
        except ValueError:
            pass
    start, end = trimmed.find("{"), trimmed.rfind("}")
    if start >= 0 and end > start:
        try:
            val = json.loads(trimmed[start:end + 1])
            if isinstance(val, dict):
                return val
        except ValueError:
            return None
    return None
