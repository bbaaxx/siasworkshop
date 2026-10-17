---
id: "session-handoff-2026-10-17"
title: "Session Handoff — 2026-10-17 (S1 live-verified; dev pause for research)"
category: "setup"
created: "2026-10-17"
updated: "2026-10-17"
related_initiatives: ["mcp-model-behavior-research--2026-10-15", "mcp-hotplug-debug-access--2026-10-17"]
tags: ["handoff","agents","llm","mcp","session-state"]
lifecycle: "stable"
knowledge_type: "reference"
confidence: "high"
source_initiatives: []
---

# Session handoff — read this first (current)

Canonical handoff as of 2026-10-17 (post live-test). Supersedes
`session-handoff-2026-10-16` (kept for history — S0/S2 build story and the
pattern-tree design live there).

**STATUS: development paused by user.** Next session opens with RESEARCH, not code:
(1) a paper coming out on related research (Minecraft × LLM), (2) an existing
Minecraft MCP server implementation to study. Digest both before touching the mod;
questions to answer are in "Resuming" below.

Reading order for a fresh agent:
1. `AGENTS.md`, then this file.
2. `setup/agent-shared-ledger` — SEQ-023 (llm.enabled gate bug), SEQ-024 (S1 live
   test + 4 fixes + MCP cooldown-bypass close), SEQ-025 (hot-plug initiative
   opened), SEQ-026 (S1 retest — live-verified end-to-end).
3. Initiative `mcp-model-behavior-research--2026-10-15` and
   `mcp-hotplug-debug-access--2026-10-17` (the two open workstreams).
4. `reference/mcp-pattern-tree-design` (protocol design), `setup/agent-ux-feedback-2026-10-16`.

## Where things stand

- **S1 (in-mod MCP path) is LIVE-VERIFIED end-to-end** in TestSidecar01: model
  decides via sidecar → claims → walks → withdraws stone bricks from a chest →
  places blocks → mazons built (user eyewitness, SEQ-026). First full
  decide→claim→execute→auto-complete cycle also closed on `comfort:light`.
- **Commits** (main): S0 `3c9db1f` → S2 `ccadc75` → S1 `8c4b2b5` → this session:
  `2eccf95` (llm.enabled gate order), `0f79f43` (shelter leak, reject logging,
  STUCK_CYCLES 4→40, release cooldown), `c3318d0` (close MCP bypass of the
  cooldown), `59aa18b` (hot-plug initiative), `7db0aca` (SEQ-026).
  **36/36 gametests green**, tree clean at `7db0aca`.
- **Initiative `mcp-model-behavior-research`**: S0/S1/S2 done and live-proven.
  Remaining: scenario corpus (Q4 — three real failure classes already identified:
  unroutable anchor, wrong/missing material, station-radius ignorance) + migration
  recommendation writeup to `reference/`.
- **Initiative `mcp-hotplug-debug-access`** (proposed): P1 sidecar `--tap` wire tap,
  P2 `agents.mcp.debugPort` localhost attach. Rationale proven live: SEQ-024's
  forensics rounds would have been a `tail -f`.
- **Known executor warts** (queued, non-blocking): `withdrawOnce` visits only the
  FIRST chest in scan order per cycle; arrival-circling evades the stuck detector
  (it counts unmoving ticks only — a real fix is a no-progress watchdog).

## Resuming after the research pause

The user returns with a fresh paper + an existing Minecraft MCP server to digest.
Capture in a wiki `reference/` page (extend the research initiative or open a
short research initiative — user decides):
- Tool catalog shape: flat vs tree, schema discipline, versioning.
- Transport: stdio vs SSE/WebSocket, multi-client attach, observation taps.
- Authorization/security model for in-game MCP (localhost scope? player-scoped?).
- Multi-step sessions: how they hold state, convergence checking, tool gating.
- Single-player vs dedicated server; mod-loader ecosystem (NeoForge relevance).
Then likely next code moves, in the user's previously stated priority order:
hot-plug P1 (tap) → corpus Q4 → migration writeup.

## Traps that cost real time (new since last handoff)

- **Server configs are PER-WORLD** (`<world>/serverconfig/`): a new world silently
  gets defaults — the S1 live test ran 18 min with the MCP flag off because the
  edit lived in another world's file. Dev fix: keep a configured copy in
  `run/defaultconfigs/siasworkshop-server.toml` (seeds every new world) AND copy
  into existing worlds. `run/saves/TestSidecar01/` and `run/defaultconfigs/` are
  already seeded for the mcp-enabled setup.
- **Javadoc-vs-code drift is real**: `openTasks()` claimed it excluded
  COMFORT_SHELTER and didn't — the live model found the hole in one session.
  Distrust descriptive comments near gates; pin behavior with tests.
- **Gate-order bugs hide in early-returns**: the MCP branch below the
  `!llm.enabled` early-return made the flag a no-op. Pure routing functions +
  gametests now pin the precedence (`route()`/`DecisionRoute`).
- **Fixes that filter a list can be bypassed by a second reader**: the release
  cooldown filtered `decide()` only; `McpTools.claim` read `openTasks()` directly
  and the spin would have persisted. When fixing a gate, enumerate EVERY caller of
  the underlying source.
- **Reject payloads must carry their reason to the log** — a bare "unknown"
  decision cost a code-reading round. `handleMcpResult` now logs reject/detail.
- **mdocs**: `mdocs command initiative.update` CLOBBERS initiative bodies
  (harness-mdocs issue #11) — hand-edit files, then validate. The validator's
  graph checks backlinks both ways (wiki `related_initiatives` ↔ initiative
  `related_wiki`); a wrong wiki id fails validation with errorCount 1.
- Gametests are 36 now; same traps as before apply (no `@GameTest(timeout=)`,
  shared batch world, production tick loop runs during tests).

## Environment facts

- M3 Max; LM Studio local (`http://127.0.0.1:1234/v1`, `prism-ml/bonsai-27b` —
  reasoning model, 30–90s per MCP decision under load). Dev client + bonsai on one
  machine = constant "Can't keep up" (10–20s bursts); game-time waits stretch.
- Live-test recipe that works: full JVM restart (quit to desktop), world
  TestSidecar01, `agents.mcp.enabled=true` with the absolute sidecar path already
  in its serverconfig; raise a build task with stone-bricks chest ≤16 blocks of
  the anchor; watch for `sidecar started (pid …)` then `decided via MCP sidecar`.
- The TestSidecar01 board still holds the unroutable `build:mazons:-20,86,0` —
  it burns one agent lap per 5-min cooldown window; cosmetic, clear it or ignore.
- mdocs: after ANY hand-edit under `mdocs/`: `./node_modules/.bin/mdocs validate`.
