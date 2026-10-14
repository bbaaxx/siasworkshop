---
id: "agents-v1-decision-pool-goals--2026-10-14"
title: "Agents v1: shared decision pool + task board + comfort goals"
status: "complete"
created: "2026-10-14"
updated: "2026-10-14"
owner: ""
tags: ["agents","decision-pool","goals","implementation"]
related_wiki: ["setup/session-recovery-2026-10-14"]
priority: "medium"
graduated: "2026-10-14"
---

## Objective
Implement the SavedData-backed shared decision pool (experience entries, tag retrieval, deterministic outcome evaluator), the village task board with claim leases, and two comfort deficit detectors (light, shelter) per reference/agent-embodiment-observation. No LLM yet — deterministic task execution only.

## Plan
- [x] Decision pool SavedData + schema v1 — `content/agents/pool/AgentDecisionPool.java` + `PoolEntry.java`: versioned NBT, size cap + LRU eviction by `last_used`, tag-match top-K retrieval (K config), trusted-after-N-successes flag
- [x] Task board SavedData — `content/agents/tasks/TaskBoard.java` + `BoardTask.java`: OPEN/CLAIMED/DONE/FAILED, claim with game-time lease, lazy + periodic lease-expiry sweep, complete/fail hooks that feed the pool evaluator
- [x] Light + shelter comfort detectors — `content/agents/tasks/ComfortDetectors.java` (server tick, config-gated): light = spawnable-dark spots within player radius; shelter = at night, bed within radius AND skylight covered; raise/clear singleton deficit tasks per player key
- [x] Wire SavedData holders + detector tick in `SiasWorkshop.java`; all behavior config-gated (`Config.java` agents section: detectors on/interval/radius/thresholds, pool maxEntries/topK/trustThreshold)
- [x] SEQ-007: observation compression pass — token estimator (~chars/4), section caps + bottom-up trimming to a config token budget (default 900), compact POI/inventory formats in `AgentObservation`
- [x] SEQ-007: verbatim debug output — Agent Inspector writes the snapshot to `debug/siasworkshop/agent-observation-<timestamp>.txt` in the game dir (chat transcription is lossy)
- [x] Gametests (new `gametest/AgentsPoolGameTest.java`): pool save/load + retrieval + cap eviction; board claim/lease-expiry/singleton deficit; detectors raise+clear light and shelter; observation budget ≤900 with mock player
- [x] `./gradlew build` + `runGameTestServer` green, diff review, `mdocs validate`

## Progress Log
- [2026-10-14T05:54:12.292Z] Created initiative via mdocs command
- [2026-10-14T14:05:00.000Z] Resumed in new session; prior session handed off via setup/session-recovery-2026-10-14 (spike committed as 8bfa3b8). Plan expanded with SEQ-007 scope (observation compression + budget gametest + verbatim snapshot-to-file).
- [2026-10-14T14:50:00.000Z] Implemented via builder subagent; coordinator review found 1 blocking (shelter detector used night-adjusted sky light >= 15 for exposure — dead code; fixed to canSeeSky) + should-fixes (lease sweeps no longer write pool anti-patterns, lease boundary now >=, complete/fail verify claimant UUID, NBT read skips malformed entries, board NBT round-trip + steal + low-budget compression tests added, resolved-task reaping at 128). `./gradlew build` green; `runGameTestServer` 16/16 (3 pre-existing + 13 new) verified independently by coordinator.
- [2026-10-14T14:56:00.000Z] Completed. Known follow-ups (next initiative scope): WorkshopAgentEntity.currentTask() still the "idle" stub until a task-execution loop claims board tasks and cites pool entries via recordSuccess; findNearestFoundation full-box scan is ~235k getBlockState per observation (fine at decision cadence, revisit if cadence rises).

## Artifacts
- `src/main/java/com/siaws/siawsmod/content/agents/pool/AgentDecisionPool.java`, `pool/PoolEntry.java` — SavedData pool, schema v1, LRU cap, tag top-K retrieval, trusted-after-N
- `src/main/java/com/siaws/siawsmod/content/agents/tasks/TaskBoard.java`, `tasks/BoardTask.java`, `tasks/ComfortDetectors.java` — board with claim/lease/steal + claimant-verified resolution; light + shelter detectors (server-tick, config-gated)
- `src/main/java/com/siaws/siawsmod/content/agents/AgentWorldData.java`, `ObservationBudget.java`, compressed `AgentObservation.java`, snapshot-to-file `AgentInspectorItem.java`
- `src/main/java/com/siaws/siawsmod/gametest/AgentsGoalsGameTest.java` — 13 tests (pool, board, detectors, budget)
- `config/Config.java` — 9 new `agents.*` knobs; `SiasWorkshop.java` tick wiring; `agentsgoalsgametest.empty.nbt` fixture; 2 lang keys