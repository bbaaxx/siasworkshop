---
id: "session-handoff-2026-10-17"
title: "Session Handoff — 2026-10-17 (research digests done; hot-plug built; next: corpus Q4)"
category: "setup"
created: "2026-10-17"
updated: "2026-10-17"
related_initiatives: ["mcp-model-behavior-research--2026-10-15", "mcp-hotplug-debug-access--2026-10-17", "research-digest-player-1-synthetic-game-agents--2026-10-17", "research-digest-jev-jeva-decision-layer--2026-10-17"]
tags: ["handoff","agents","llm","mcp","session-state"]
lifecycle: "stable"
knowledge_type: "reference"
confidence: "high"
source_initiatives: []
---

# Session handoff — read this first (current)

Canonical handoff as of 2026-10-17 evening. This file was REWRITTEN in place:
its earlier revision ("S1 live-verified; dev pause for research") is superseded —
the research pause completed and hot-plug shipped the same day. The pre-pause
story (S0/S1/S2 build history, S1 live-verification forensics) lives in
`session-handoff-2026-10-16` and ledger SEQ-020..026.

**STATUS: development ACTIVE. Next code move is corpus Q4** (scenario corpus +
assertions), then the migration recommendation. The user sequenced: "start
building complexity from simple patterns" — hot-plug landed first precisely so
the coordinator has full visibility during the next stages.

Reading order for a fresh agent:
1. `AGENTS.md`, then this file.
2. `setup/agent-shared-ledger` — SEQ-028 (research digests), SEQ-029 (hot-plug
   build), SEQ-030 (this wrap).
3. The three digest pages: `reference/player-1-game-agents`,
   `reference/minecraft-mcp-servers`, `reference/decision-layer-jev-jeva`.
4. Initiative `mcp-model-behavior-research--2026-10-15` — the one open
   workstream (remaining: corpus Q4 + migration writeup).
5. `reference/mcp-pattern-tree-design` — now includes the "Debug access
   channel (hot-plug)" section.

## Where things stand

- **Research pause: COMPLETE.** Three digests, three completed initiatives:
  - *Player-1* (paper, arXiv 2610.20584): pixels→RL game control, 38.33%
    MineDojo-300. We take its verifiable-reward recipe (dense score increments,
    invalid-call penalty + wait fallback, reasoning-length penalty, no terminal
    penalties) and coverage-over-trials metrics as the corpus Q4 template; its
    randomization axes become corpus diversity axes (targets our three failure
    classes: unroutable anchor, wrong/missing material, station-radius
    ignorance). SFT/profile init dominating RL (+34pp vs +4pp) validates that
    sidecar profiles are the value layer.
  - *Minecraft MCP landscape*: the yuniko server (deep digest, Apache-2.0) and
    every other open MC-MCP server are Mineflayer protocol-bots — client-side,
    no server code. Our mod-side registry + normalization sidecar is
    unrepresented (independent design validation); multi-client attach was
    unclaimed until our P2. Adoption candidates recorded: executor
    per-move timeout + no-progress watchdog, hard-reject vs soft-world-no
    taxonomy, observation ring buffer.
  - *JEV + JEVA (decision layer)*: JEV = TypeSafe's hosted system-one model
    (cloud/usage-priced — rejected by user constraint). JEVA = the open
    architecture (null-aware / permutation-invariant / option-scalable /
    cacheable; no weights released). **Local alternatives verified**: JevK5-4B
    GGUF (llama.cpp, Apache-2.0, ~0.6s/short decision on Apple Silicon; weak
    null handling) and Kev-4B (MLX, validated on 32GB Macs, 1–255 options,
    excellent OOD calibration; option-order caveat). Tiered recommendation in
    the wiki: T0 = shuffle + separate coverage noul + JEVA metrics into corpus
    Q4 (zero infra); T1 = self-host JevK5 or Kev-4B as localhost typed-decision
    endpoint; T2 = Jeva-style head only if the catalog outgrows T1.
- **Hot-plug debug access: DONE** (initiative complete, ledger SEQ-029).
  - P1: `sidecar.py --tap <file>` — wire tap, `{"ts","dir","msg"}` JSONL,
    replayable. Live recipe: `tail -f run/wire.jsonl` (decisions only: grep
    `'"dir": "in"'` + `tools/call`).
  - P2: `agents.mcp.debugPort > 0` (+ `agents.mcp.enabled`) →
    `content/mcp/McpDebugServer` on 127.0.0.1, READ-ONLY catalog
    (observe_self/observe_board; writes rejected by name). Attach:
    `python3 tools/mcp_probe.py --port <p> list|board|self --agent <uuid>`.
  - 38/38 gametests (36 + 2 debug-transport), python bridge + tap tests PASS.
- **Known executor warts** (unchanged, queued polish): `withdrawOnce` visits
  only the FIRST chest in scan order; arrival-circling evades the stuck
  detector (a no-progress watchdog is the real fix).
- **Open workstream**: `mcp-model-behavior-research` — corpus Q4 (now armed:
  tap produces recorded artifacts; Player-1 reward recipe + JEVA flip/all-order/
  null-AUROC metrics to score with; three real failure classes identified) and
  the LlmClient→MCP-client migration recommendation writeup.

## Next moves (user's priority order)

1. **Corpus Q4** — scenario corpus + assertions, replayable offline against
   any endpoint; the tap file doubles as the recorded-artifact format.
2. **Migration recommendation** → `reference/` wiki (closes the research
   initiative).
3. Tier-0 decision-layer fixes (shuffle + coverage question) fold into the
   corpus metrics work; Tier-1 (self-hosted JevK5/Kev-4B evaluation) after.

## Traps that cost real time (accumulated; the S1-era ones still apply)

- **Server configs are PER-WORLD** (`<world>/serverconfig/`): seed
  `run/defaultconfigs/siasworkshop-server.toml` AND copy into existing worlds.
- **Javadoc-vs-code drift is real**; **gate-order bugs hide in early-returns**
  (pin pure routing fns); **list-filter fixes are bypassable by second readers**
  (enumerate EVERY caller); **reject payloads must carry their reason**.
- **mdocs state: drive the CLI, not the session MCP tools.** This session the
  MCP server held divergent IN-MEMORY state (fresh IDLE while disk said
  EXECUTE; `initiative.create` returned success but never persisted). Use
  `./node_modules/.bin/mdocs reset|resume|step` + hand-edited files, then
  `mdocs validate`. Details: `setup/mdocs-kimi-plugin-fix`.
- **Gametest structure files resolve per class name**: a new `*GameTest` class
  needs `src/main/resources/data/siasworkshop/structure/<classlower>.empty.nbt`
  (copy an existing one) or the server crashes with "Missing test structure".
- **Validator reciprocal backlinks**: every `related_wiki` entry on an
  initiative needs the initiative id in the page's `related_initiatives`, and
  vice versa — a 6-page fan-out for the JEV/JEVA initiative; validate before
  claiming done.
- Gametests are 38 now; same standing traps (no `@GameTest(timeout=)`, shared
  batch world, production tick loop runs during tests, claim your OWN key).

## Environment facts

- M3 Max; LM Studio local (`http://127.0.0.1:1234/v1`, `prism-ml/bonsai-27b` —
  reasoning model, 30–90s per MCP decision under load). Game + model on one
  machine = constant "Can't keep up" bursts.
- Live-test recipe: full JVM restart, world TestSidecar01, `agents.mcp.enabled=true`
  with the ABSOLUTE sidecar path in its serverconfig; raise a build task with
  stone-bricks chest ≤16 blocks of the anchor; watch `sidecar started (pid …)`
  then `decided via MCP sidecar`. Add `--tap /abs/path/run/wire.jsonl` to the
  command for the forensics-free round.
- The TestSidecar01 board still holds the unroutable `build:mazons:-20,86,0` —
  burns one agent lap per 5-min cooldown window; cosmetic.
- mdocs: after ANY hand-edit under `mdocs/`: `./node_modules/.bin/mdocs validate`.
