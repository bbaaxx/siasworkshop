---
id: "agents-v1-spike-entity-observation--2026-10-14"
title: "Agents v1 spike: hybrid entity + observation snapshot"
status: "done"
created: "2026-10-14"
updated: "2026-10-14"
owner: ""
tags: ["agents","entities","observation","implementation","spike"]
related_wiki: ["setup/session-recovery-2026-10-14","setup/gametest-notes-1-21-1"]
priority: "medium"
---

## Objective
First implementation increment of the agent design (reference/agent-embodiment-observation): one humanoid workshop-agent entity (custom PathfinderMob body; FakePlayer interaction delegate can be stubbed) with a server-side observation snapshot generator that prints the text world-state (self, village, players, blocks, entities, environment, available actions) via a debug item/command. No LLM calls in this spike. Vanilla humanoid rendering; no GeckoLib dependency yet.

## Plan
- [ ] Entity + registration holder in init/ + renderer
- [ ] Observation snapshot generator (token-budgeted sections)
- [ ] Debug trigger (item or command) + gametest
- [ ] build + gametest green, review, validate

## Progress Log
- [2026-10-14T05:54:12.292Z] Created initiative via mdocs command
- Spike COMPLETE and green. Implemented: WorkshopAgentEntity (PathfinderMob, 27-slot inventory, synced owner UUID, NBT persistence), AgentInteractionDelegate stub (FakePlayer seam), AgentObservation (6 budgeted text sections + filtered action space), AgentInspectorItem (config-gated debug tool, datagen model), vanilla HumanoidMobRenderer with zombie texture placeholder, init/SiasWorkshopEntities holder, 2 GameTests. Build PASS, runData PASS (agent_inspector model generated), runGameTestServer PASS (2/2). Reviewer: no blocking findings. Learnings captured in wiki setup/gametest-notes-1-21-1 (GameTestHolder discovery, classname.template naming, singular structure/ folder, Entity NBT hook signatures). Fixture regeneration script: python3 heredoc writing gzipped NBT (size [3,3,3], air palette, DataVersion 3955) to src/main/resources/data/siasworkshop/structure/agentsgametest.empty.nbt.
- [2026-10-14T06:43:10.032Z] Marked done via mdocs command

## Artifacts
