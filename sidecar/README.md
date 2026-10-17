# Sia's Workshop — MCP sidecar (S0 spike)

Python sidecar + spike harness for the MCP workstream
(initiative `mcp-model-behavior-research--2026-10-15`, design:
`mdocs/wiki/reference/mcp-pattern-tree-design.md`). **Stdlib only** — no pip
dependencies (D9: the whole path is feature-flagged and must stay lightweight).

## Topology (S0)

```
run_spike.py (driver, plays the sidecar's client logic)
  ├─ spawns: mockmod.py  — plays the mod's MCP-SERVER role (D1), scripted world
  │            MCP over child stdio: driver writes mock stdin / reads mock stdout
  └─ calls:  model.py    — OpenAI-compatible chat (LM Studio local / OpenRouter cloud)
```

In production the roles' spawn direction reverses (the mod spawns the sidecar) but the
wire is identical: newline-delimited JSON-RPC, client writes requests on its stdout,
reads responses from its stdin (D2).

## Modes (the A/B surface)

| mode        | catalog delivery     | reply contract            | models              |
|-------------|----------------------|---------------------------|---------------------|
| `v1`        | none (LlmPrompts replica) | `{"action","args"}`  | all (baseline)      |
| `mcp-prompt`| rendered into prompt | `{"tool","args"}`         | local / any         |
| `mcp-native`| native function tools | tool_calls               | cloud / capable     |

All modes observe through the same MCP pipe, validate against the same catalog, and
execute through the same `tools/call` boundary — measured differences attribute to the
model-facing surface, not the plumbing. (For parity the `mcp-native` leg offers only the
decision effect set; when `observe.*` tools were offered too, the cloud model
spontaneously re-observed 2/3 runs instead of deciding — good multi-step instinct,
recorded for S2, but not a decision-shaped reply.)

## S2: the building pattern walk

`--walk` runs the first real pattern tree end-to-end: `building.think_through_request`
→ `analyze_terrain` → `design` → `select_materials` → `create_blueprint` →
`apply_blueprint` → poll `observe_task` until the build converges. The mock simulates
the mod-side machinery (site ranking, card retrieval, material bills, blueprint store,
build progress one tick per poll). The session enforces D5 situational gating: a tool
only works after the pattern card that offers it has opened — walk 2 in
`replay/walk-script.json` asserts the gate rejects an early `apply_blueprint` and the
model recovers.

```bash
# asserted walk replays (no network): 3 walks incl. a gate-reject recovery
python3 run_spike.py --walk --replay replay/walk-script.json --scenario scenarios/walk_build.json

# real model walks (cloud fast, local slow)
python3 run_spike.py --cloud-config ../run/saves/TestLLM_01/serverconfig/siasworkshop-server.toml \
    --walk --iterations 2 --scenario scenarios/walk_build.json
python3 run_spike.py --endpoint http://127.0.0.1:1234/v1 --model prism-ml/bonsai-27b \
    --walk --iterations 1 --scenario scenarios/walk_build.json
```

## Quickstart

```bash
cd sidecar

# 1. deterministic plumbing self-test (no network; the gametest analog)
python3 run_spike.py --replay replay/script.json --scenario scenarios/stone_bricks_chest.json

# 2. local leg (LM Studio must be serving the model)
python3 run_spike.py --endpoint http://127.0.0.1:1234/v1 --model prism-ml/bonsai-27b \
    --mode all --iterations 1 --scenario scenarios/stone_bricks_chest.json

# 3. cloud leg (apiKey read from the test-world serverconfig, never printed)
python3 run_spike.py --cloud-config ../run/saves/TestLLM_01/serverconfig/siasworkshop-server.toml \
    --mode all --iterations 3 --scenario scenarios/dark_room.json
```

Metrics append to JSONL (`--out`, default `metrics-<ts>.jsonl`); a summary table
(ok% / malformed% / latency / completion tokens) prints at the end.

## Files

- `catalog.py` — tool descriptors, schema-subset validator (the D7 boundary),
  native/prompt renderers, v1 prompt replica. Wire names use underscores (MCP tool
  names must match `^[a-zA-Z0-9_-]{1,64}$`; dotted ids stay the internal alias).
- `mcp.py` — newline-delimited JSON-RPC client (initialize handshake, tools/list,
  tools/call) + mock spawner.
- `mockmod.py` — the mod's server-role stand-in: scripted scenario world (observation
  + open tasks), structured rejects for unknown tools/args/keys.
- `model.py` — stateless OpenAI-compatible chat client (failure taxonomy mirrors
  `LlmClient.LlmResult`), per-model profiles (D8), `FakeModel` for replay.
- `session.py` — one decision in one mode; uniform observe → decide → validate →
  execute; `Decision` outcome taxonomy (`malformed_json`/`unknown_tool`/`bad_args`/
  `model_error:*`, `exec_ok`/`exec_reject:*`).
- `run_spike.py` — A/B runner, replay self-test with asserted expectations, summary.
- `scenarios/` — `stone_bricks_chest` (build task + comfort task), `dark_room`
  (night light task).
- `replay/script.json` — scripted raw model replies (valid, fenced, prose-wrapped,
  wrong-shape, unknown tool, bad args, exec-reject, native tool_calls, garbage).

## S1: the in-mod bridge

`sidecar.py` is the real child-role sidecar the mod spawns (config `agents.mcp.enabled`,
default OFF). It speaks the client role over its own stdio: initialize handshake, serves
`decide(agent)`, and for each decision pulls observation/board via mod tools, asks the
model (or `--replay` scripted replies), validates, and executes claim/rest/say via mod
tools. The Java side (`content/mcp/`): `McpSidecar` (transport + lifecycle + failure
collapse), `McpTools` (the mod's catalog + server-thread dispatch + structured rejects),
routed from `AgentDecisionLoop` ahead of the direct `llm.*` path, with template fallback
on any sidecar failure.

```bash
# bridge test: sidecar.py against mockmod.py over crossed pipes (production topology)
python3 test_sidecar_bridge.py

# what the mod would run (dev): point agents.mcp.command at an absolute path, e.g.
python3 /path/to/sidecar/sidecar.py --endpoint http://127.0.0.1:1234/v1 --model prism-ml/bonsai-27b
```

Java gametests (`AgentsMcpGameTest`) cover the mod side against an in-JVM fake peer:
handshake + catalog serving, a full decide cycle claiming on the real board, structured
rejects for malformed calls, and template fallback on a dead sidecar.

## Encoded traps (from the ledger — do not relearn)

- Never send `response_format` — LM Studio (llama.cpp) rejects `json_object` (SEQ-011).
- Strip trailing slashes from endpoints — OpenRouter 404s double-slash paths (SEQ-014).
- Reasoning models (bonsai-27b) burn 500–900 hidden tokens: `max_tokens` 1024,
  timeout 120 s; `empty_content:length` is a budget burnout, counted as model_error.
- `~`-prefixed OpenRouter model ids are valid; keep the prefix (SEQ-014).
- Local models: constrained-schema JSON (prompt mode) is the reliable path; native
  function calling is a per-model profile flag, not the default (D8).
