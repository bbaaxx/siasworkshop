---
id: "session-handoff-2026-10-26"
title: "Session Handoff — 2026-10-26 (build-executor fixes DONE + break primitive shipped; ACTIVE: agents action-surface expansion — next: P1 gather/craft primitives)"
category: "setup"
created: "2026-10-26"
updated: "2026-10-26"
related_initiatives: ["agents-action-surface-expansion-hands-materials-economy-roadmap", "build-executor-self-entombment-station-material-gaps-live-test-2026-10-22", "executor-wart-polish-impossible-task-timeout-no-progress-watchdog-multi-chest-withdrawonce-self-collision-guard", "post-polish-batch-kev-coverage-recalibration-comfort-shelter-executor-mdocs-graph-hygiene"]
tags: ["handoff","agents","session-state","build","knowledge","actions"]
lifecycle: "stable"
knowledge_type: "reference"
confidence: "high"
source_initiatives: []
---

# Session handoff — read this first (current)

Canonical handoff as of 2026-10-26 (fifth revision; supersedes
`session-handoff-2026-10-17` v4). Arc since v4: executor wart polish shipped →
**2026-10-22 live test** exposed three build-executor failure classes →
diagnosed from logs + wire + persisted board → **staging/extrication + the
agent knowledge ledger** (10-22) → **user direction: vastly expand the action
surface** → **break primitive** as slice 1 (10-25).

## STATUS

- **`build-executor-self-entombment-…-2026-10-22` — DONE, verified.** Builds
  stage from `nearestStagingCell` (self-extrication; unreachable anchors
  stage nearby and place remotely); materials-short builds PARK in the
  knowledge ledger and auto-resume on restock — no `missing_material`
  terminal-fail, no "awaiting" states (user direction: the ledger is the
  substrate for future supply surfaces).
- **`agents-action-surface-expansion-hands-materials-economy-roadmap` —
  ACTIVE (workflow at PLAN with this initiative active). Slice 1 (break
  primitive) DONE + verified.** Guarded, tier-gated, LLM-composable breaking:
  `BreakSupport` (primitives pkg), `break_block` MCP tool with teaching
  rejects (`needs_tool:<tier>_<kind>`), BUILD executor clears obstructions,
  sidecar catalog + generic agent-arg injection. Tier-0 instrumentation keeps
  the board-decision set {claim,rest,say} on purpose.
- **5 local commits ahead of `origin/main`, NOT pushed** (b55cf87 → 04aa464).
  User decision pending on push.
- Verification snapshot: **55/55 gametests**, python suite green (bridge/tap/
  tap-import/corpus/decision-probe/calibrate + S0 replay 20/20), `mdocs
  validate` clean, `./gradlew build` green.

## Reading order for a fresh agent

1. `AGENTS.md`, then this file.
2. `setup/agent-shared-ledger` — SEQ-034 (tier-0 numbers) → **SEQ-036
   (2026-10-22 live-test postmortem + staging/ledger fixes)** → **SEQ-037
   (2026-10-26 wrap: break primitive + handoff)**.
3. **`reference/agent-knowledge-system`** — the ledger design + user direction
   (no awaiting; LLM composes primitives toward goals).
4. Initiative `agents-action-surface-expansion-…-2026-10-23` — the roadmap
   (P0→P4) + slice-1 as-built record. **Resume this initiative next.**
5. `setup/gametest-notes-1-21-1` — the trap list (two new rounds of traps
   since 10-21; read before writing ANY gametest).
6. `setup/test-run-protocol-2026-10-20` — live-test recipe (Phase 2 config:
   per-world serverconfig + absolute sidecar command + full JVM restart).

## Where things stand

- Action surface now: executors LIGHT / SHELTER / BUILD (staging + extrication
  + ledger parking + obstruction clearing); LLM actions claim/rest/say/build;
  MCP tools observe_self/observe_board/claim/**break_block**/rest/say;
  breaking guardrails = config gate + radius + vanilla Tool-component tiers +
  protected (block entities/beds); drops → agent inventory; tool durability.
- Knowledge: `AgentKnowledge` SavedData (`siasworkshop_agent_knowledge.dat`)
  with MaterialNeed entries (BLOCKED → RESOLVED_RESTOCKED/PLACED/GONE),
  rechecked every `agents.knowledgeRecheckSeconds` (default 60s).
- The user's composition philosophy (recorded in the roadmap initiative):
  **no preset supply paths** — needs arise from goals; the means-ends chain
  (food → farm → hoe → planks → sticks → logs; seeds → break grass) is the
  LLM's reasoning over validated primitives. Every new capability must land
  as an LLM-callable effect tool with teaching rejects.

## Next moves (resume order per the roadmap; user can re-pick)

1. **P1 primitives: gather (punch trees/leaves, collect drops) then craft
   (planks→sticks→tools at a crafting table).** This closes the loop opened by
   slice 1: a `needs_tool` reject becomes resolvable by the agent end-to-end.
   Shapes mirror `BreakSupport` + `break_block`: primitive, guardrails,
   effect tool, gametests, sidecar catalog entry.
2. **Ledger-driven mine loop** — blocked MaterialNeeds raise MINE tasks;
   progressive multi-tick digging (slice 1 breaking is instant per block).
3. P0.2 deposit/withdraw logistics; then P2 trade/search, P3 decision-layer
   (pattern-tree catalog growth), P4 knowledge entry types
   (SOURCE_DISCOVERED, RECIPE_LEARNED, …).
4. User-side pending: push the 5 commits; a **live test of the break
   primitive** (recipe in test-run-protocol Phase 2; watch the model meet
   `needs_tool` and compose `break_block`).

## Traps that cost real time (new since 10-21; full list in gametest-notes)

- **Never assert `leaseUntilGameTime` within one game tick** — several manual
  `runExecutionPass` calls in one body read the same `getGameTime()`; renewal
  is invisible (`610 > 610` false). Assert state execution actually changes.
- **Gametest-world agents cannot path** at the far-flung random positions (no
  navigation paths, entities may not even tick/fall; the terrain is real
  worldgen, not flat). No pre-existing test ever walked. Drive decisions, not
  locomotion: simulate walks with `agent.moveTo(...)`, author dirt platforms,
  and beware walk-dependent tests passing on worldgen luck (natural stone at
  the anchor counts as placed).
- **try/finally `agent.discard()` in every test that spawns an agent** — a
  pre-discard assert failure leaks the agent; the unfiltered production decide
  then claims later batches' tasks (symptoms: contested light claims, missing
  shelter roof cells, shifting failure sets one/two batches downstream).
- **`NEEDS_STONE_TOOL` is an ORES-only tag** — plain stone is hand-breakable
  for the guard (drops still need a pickaxe via loot conditions). Don't use
  stone to test tier refusals; use iron ore.
- Drive mdocs via the **CLI** (`./node_modules/.bin/mdocs …`), not the session
  MCP resume tools (initiative-not-found bug + `initiative.update` CLOBBERS
  the body — direct file edits + validate instead).

## Environment facts

- Repo: `/Users/bbaaxx/Code/projects/siasworkshop`, branch `main`.
  `./gradlew build | runGameTestServer | runData | runClient` — gametest world
  wipes per run (`run-gametest/`), serverconfig seeded with
  `decisionIntervalSeconds = 600` etc.
- Live-test world `run/saves/TestSidecar01` holds stale 2026-10-20/22 board
  tasks and half-built cubes (gitignored; SafeData: `data/siasworkshop_*.dat`
  are parseable gzipped NBT — `.tmp/inspect_board.py` is a working dumper).
  Fresh worlds via per-world `serverconfig/siasworkshop-server.toml`.
- Sidecar: stdlib-only python under `sidecar/`; model endpoint defaults to LM
  Studio `http://127.0.0.1:1234/v1` + `prism-ml/bonsai-27b`; `--tap
  run/wire.jsonl` records the wire. kev/Jev decision models live in `~/.cache`
  + venv `~/.venvs/kev` (both stopped; recalibration follow-up parked).
- `docker-agent.yaml` carries an uncommitted user edit (sandbox allowlist) —
  intentionally left out of agent commits.
