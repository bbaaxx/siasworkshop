---
id: "build-executor-self-entombment-station-material-gaps-live-test-2026-10-22"
title: "Build executor: self-entombment + station/material gaps (live test 2026-10-22)"
status: "done"
created: "2026-10-22"
updated: "2026-10-22"
owner: ""
tags: ["agents", "build", "knowledge", "ledger"]
related_wiki: ["reference/agent-knowledge-system", "setup/gametest-notes-1-21-1"]
---

## Objective

Fix the build-executor failure classes exposed by the 2026-10-22 live test in
TestSidecar01: three mazons cubes (3×3×3 stone-bricks) were rebuilt with
defects and every task terminal-failed. Root causes (from logs + wire +
persisted board): (1) the agent entombed itself in the footprint — the entomb
guard skipped its own cells forever and the build could never complete;
(2) the 2-block 3D arrival against the anchor corner stranded elevated
anchors (unreachable terminal-fail, nothing built); (3) a mid-build materials
shortfall terminal-failed the task and stranded the partial structure.

## Plan

1. Diagnose from run/logs + run/wire.jsonl + the persisted task board. (done)
2. Fix 1+2: stage builds from `nearestStagingCell` — the nearest standable
   cell to the anchor OUTSIDE the remaining footprint; `staging == null` →
   unroutable release (three strikes terminal-fail, existing semantics).
3. Fix 3 (user direction): NO "awaiting materials" state. A materials
   shortfall parks the task in a durable per-world **knowledge ledger**
   (`AgentKnowledge`, first slice of the in-game shared knowledge system):
   blocked tasks are unclaimable (openTasks/claim filter), visible on
   observe_board (`blocked_tasks`), and re-activated automatically when the
   station chests near the anchor hold the remaining quantity
   (`recheckBlocked`, interval `agents.knowledgeRecheckSeconds`, default 60s).
   Future supply surfaces (gather/harvest/mine/trade/search) resolve the same
   entries — an entry with no known source is the planner's input.
4. Gametests: extrication completion, floating-anchor staging, ledger
   park→restock→resolve→complete.
5. Verify: full gametest suite + python suite + mdocs validate.

## Progress Log
- [2026-10-22T19:31:50.999Z] Created initiative via mdocs command
- [2026-10-22] Diagnosis: logs + wire.jsonl (both sessions) + board save parsed. Ground truth: `-20,81,-5` FAILED missing_material (partial cube + own-cell holes), `-12,81,-1` FAILED obstructed (top corner), `-20,86,0` FAILED unreachable (3× unroutable, elevated anchor). All three tasks were raised 2026-10-20 and persisted in the save.
- [2026-10-22] Implemented: `AgentKnowledge` SavedData + `AgentWorldData.knowledge` + config `agents.knowledgeRecheckSeconds`; `executeBuild` staging + material-block path; `openTasks` blocked filter; `McpTools` blocked_tasks listing + explicit claim reject. Wiki `reference/agent-knowledge-system` written.
- [2026-10-22] Gametest iteration: first run of the 3 new tests — 2 failed lease_expired with zero executor activity (leftover old anchor-arrival gate intact above the new staging block; agent starvation debugged via fail-fast lease probes). Own batch `agentsBuildStaging` for the new tests. Rerunning.
- [2026-10-22] Verification complete: 51/51 gametests (3 new pins), python suite green (bridge/tap/tap-import/corpus/decision-probe/calibrate + S0 replay 20/20), `mdocs validate` clean (0 errors/0 warnings), `./gradlew build` green.
- [2026-10-22] Debug-round lessons (now in setup/gametest-notes-1-21-1): (1) never assert lease renewal within one game tick — `now` is identical across manual passes so renewal is invisible; (2) gametest-world agents cannot path at the far-flung random positions (no navigation paths, entities may not tick) — pre-existing tests never walked (spawned within the old 2-block arrival), so the new tests simulate the walk with `moveTo` and exercise the remote placement; (3) a "passing" walk-dependent test can pass on worldgen luck (natural stone at the anchor counts as placed) — authored platforms + simulated walks make geometry deterministic.

## Artifacts
- `src/main/java/com/siaws/siawsmod/content/agents/knowledge/AgentKnowledge.java` (new)
- `src/main/java/com/siaws/siawsmod/content/agents/AgentDecisionLoop.java` (staging, material block, ledger recheck tick, openTasks filter)
- `src/main/java/com/siaws/siawsmod/content/mcp/McpTools.java` (blocked_tasks, claim reject)
- `src/main/java/com/siaws/siawsmod/config/Config.java` (agents.knowledgeRecheckSeconds)
- `src/main/java/com/siaws/siawsmod/gametest/AgentsExecutorGameTest.java` (3 new tests)
- `mdocs/wiki/reference/agent-knowledge-system.md` (new)
