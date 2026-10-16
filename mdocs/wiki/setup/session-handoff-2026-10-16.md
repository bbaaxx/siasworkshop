---
id: "session-handoff-2026-10-16"
title: "Session Handoff — 2026-10-16 (agents + LLM workstream)"
category: "setup"
created: "2026-10-16"
updated: "2026-10-16"
related_initiatives: ["mcp-model-behavior-research--2026-10-15"]
tags: ["handoff","agents","llm","mcp","session-state"]
lifecycle: "stable"
knowledge_type: "reference"
confidence: "high"
source_initiatives: []
---

# Session handoff — read this first (current)

Canonical handoff as of 2026-10-16 evening. Supersedes `session-recovery-2026-10-14`
(kept for history — the agents-v1 build story and the coordinator incident live there).

Reading order for a fresh agent:
1. `AGENTS.md`, then this file.
2. `setup/agent-shared-ledger` — SEQ-011 (live LLM test + 4 wire bugs), SEQ-012/013
   (MCP initiative + user direction), SEQ-014 (cloud round PASSED), SEQ-015/016
   (playtest feedback + decisions), SEQ-017 (state visibility + gametest traps).
3. `setup/agent-ux-feedback-2026-10-16` — the user's design intent, verbatim-ish.
4. Initiative `mcp-model-behavior-research--2026-10-15` — the NEXT workstream.
5. `reference/local-llm-feasibility` (wire-compat notes), `setup/gametest-notes-1-21-1`.

## Where things stand

- **Agents v1 is complete and live-tested**: entity, observation, pool/board,
  detectors, LLM client, build queue, decision/execution loop, state visibility.
  Local (LM Studio) AND cloud (OpenRouter, `~deepseek/deepseek-pro-latest`) both
  verified end-to-end: `decided via LLM: claim {"task":"<exact key>"}` observed.
- **Commits** (main): 8bfa3b8, 3cc616c, aa17226 (v1 slices) → f974a47, c9ebf8d,
  212f147 (LLM wire fixes) → 2daa823 (prompt redesign + apiKey) → a322111,
  3414e67 (cloud fixes) → f1e1c71 (state visibility). **30/30 gametests green**,
  tree clean at `e35aca9`.
- **State visibility (f1e1c71)**: nameplate states (deciding… / working · task ±
  phase / resting / idle), `say` broadcasts chat, `agents.debugActivityLog`
  (default off) appends JSONL to `run/debug/siasworkshop/agent-activity.log`.
- **User's north star (SEQ-016)**: progressively move decisions to the LLM (highest
  abstraction; sequential JSON plans the mod queues/executes) and actions to the
  mod; task board stays (inter-agent collaboration); Python MCP sidecar approved
  but must sit behind a default-OFF feature flag (no Python dependency for
  CurseForge installs). Furthest horizon (model redefines action implementations)
  is long-term research ONLY — not committed scope.

## Pending user-side test (check first if the game has run since)

The user planned one more local test: set `agents.debugActivityLog = true` in
`run/saves/TestLLM_01/serverconfig/siasworkshop-server.toml`, then either
`/siasworkshop build mazons` (chest with stone bricks within 16 blocks) or the
roofed dark room, agent nearby. Note: an idle agent with NO open tasks shows a
bare-name nameplate and produces zero loop log lines — that is correct behavior,
not a bug (the night shelter task is hidden from decisions since 3414e67; the
light task needs a roofed dark spot). After such a run, read
`run/debug/siasworkshop/agent-activity.log` for the full decision trace.

## Next workstream: MCP sidecar (initiative mcp-model-behavior-research)

User-approved direction (SEQ-013/016): MCP **sidecar** owns all model variance
(normalization, per-model profiles); the mod exposes its capability catalog as
MCP **tools** — composable **pattern trees**, not mapped verbs (world = observable
state machine; LLM plans high-level, mod executes leaf effects). Prerequisites
done (state visibility). To start:

1. `mdocs reset` → step through to EXECUTE; adopt/update the initiative.
2. **Design first**: pattern-tree descriptor format (category → patterns →
   effects), reference walk = the building workflow (think through a build
   request → analyze terrain → design → select materials → create blueprint →
   apply blueprint). Design doc to `reference/` wiki before code.
3. Then the minimal Python sidecar spike: current 3 decision tools against one
   local + one cloud model; measure latency + malformed-decision rate vs v1.
4. Feature flag from day one: MCP client path default-OFF in config.

## Traps that cost real time (don't relearn)

- **Quit-to-menu ≠ client restart**: classes and ModConfigSpec bake at JVM start;
  re-entering a world keeps old code/spec. Config value edits hot-reload; spec
  changes need a true restart. Verify constants with `javap` on build/classes
  when config behaves impossibly (a commit once claimed a change it didn't contain).
- **Out-of-range config values are silently "corrected" at load** — re-read the
  file after the game loads before assuming an edit stuck.
- **LM Studio (llama.cpp)**: rejects `response_format: json_object` (HTTP 400 →
  ClosedChannelException client-side); reasoning models burn the whole token
  budget on hidden thinking (bonsai-27b: ~500–900 tokens); answers in code fences;
  binds IPv4 only, and dev launchers set `-Djava.net.preferIPv6Addresses=system`
  so `localhost` can fail — endpoint default is `127.0.0.1`.
- **OpenRouter 404s double-slash paths** — trailing slash in endpoint 404s;
  client strips one now, but keep the config clean anyway. `~`-prefixed model ids
  are valid (required prefix family).
- **Gametest**: world persists between batches — tests MUST discard spawned
  agents or leftovers race later batches via the shared board. Each new
  `@GameTestHolder` class needs its own `<lowercase-class-name>.empty.nbt` in
  `data/siasworkshop/structure/`. Production tick handlers run during tests.
- **Dev server lags hard** (Can't keep up ~15s/30s) — game-time waits stretch.

## Environment facts

- M3 Pro; LM Studio local (`http://127.0.0.1:1234/v1`, serves
  `prism-ml/bonsai-27b` among others); cloud via OpenRouter from the test world
  config (`apiKey` present — do not print it).
- Dev client JVM args live in `build/moddev/clientRunVmArgs.txt` (watch for the
  IPv6 flag above).
- mdocs: after ANY hand-edit under `mdocs/`: `./node_modules/.bin/mdocs validate`
  (index sync is automatic in current tooling).
