---
id: "agents-v1-llm-build-knowledge--2026-10-14"
title: "Agents v1: LLM client + build knowledge"
status: "complete"
created: "2026-10-14"
updated: "2026-10-15"
owner: ""
tags: ["agents","llm","build","implementation"]
related_wiki: ["setup/session-recovery-2026-10-14"]
priority: "medium"
graduated: "2026-10-15"
---

## Objective
Config-gated LLM endpoint client (OpenAI-compatible, default http://localhost:11434/v1, off-thread executor + server.execute hop-back, template fallback) per reference/local-llm-feasibility; build queue over own templates + player-imported .schem/.nbt blueprints (own parsers); build-card JSON corpus loader per reference/agent-embodiment-observation.

## Plan
- [x] LLM client `content/llm/` — OpenAI-compatible chat completions (java.net.http, no new deps), config: llmEnabled(false), llmEndpoint(default `http://localhost:1234/v1`), llmModel(default `prism-ml/bonsai-27b`), llmTimeoutSeconds(30), llmMaxTokens(64), llmTemperature(0.3); dedicated single-thread executor, hop back via `server.execute`, one request in flight per agent, defensive JSON parse, silent template fallback on any failure (offline-first: no player-visible errors)
- [x] Blueprint model `content/agents/build/` — Blueprint{name, size, List<Placement(block, relPos)>} + own parsers: Sponge .schem v2/v3 and vanilla structure .nbt (our own format interpretation on top of vanilla NBT IO — no copied parser code); import folder `config/siasworkshop/blueprints/` scanned at server start
- [x] Build queue — player-enqueueable blueprint tasks (server command `/siasworkshop build <name> <x> <y> <z>` v1; foundation GUI later), BoardTask.Type.BUILD already reserved; materials sourced from agent inventory after withdraw-from-nearby-chest step; missing materials → fail(reason) (legit pool anti-pattern)
- [x] Build-card datapack JSON loader — `data/<ns>/siasworkshop_build_cards/*.json` {name, style_tags[], size, materials_list{}, palette[], step_summary, source_note}; loaded on datapack reload; tag-match retrieval for prompt injection (retrieve-and-inject, never browse)
- [x] Agent decision/execution loop — server tick, config cadence (agentsDecisionIntervalSeconds, default 30): idle agent → observation (budget config) → pool.retrieve(topK) → LLM picks one JSON action from the validated action space (parse+validate, else fallback) or template fallback: claim highest-priority OPEN task (comfort first), execute fixed plans (light: path to deficit, place torches from inventory, lease renewals; shelter/build per queue); resolution via claimant-verified board API + recordSuccess/recordOutcome citations; currentTask() wired to claimed task; adds TaskBoard.release(taskId, claimant) for clean unclaim (no pool write)
- [x] Grief guardrails — break/place only within village-bounds radius of an approved anchor (task/deficit/blueprint anchor); block whitelist per task type (torch for light, blueprint palette for builds); every executed action logged (debug log + chronicle hook point)
- [x] Gametests — parsers round-trip (fixture .schem v3 + .nbt written in-test), build-card codec parse, LLM client against a loopback stub HTTP server (success/timeout/malformed → fallback paths), light-task end-to-end (claim → place torch → detector clears → DONE → pool success), build task places blueprint blocks from mocked inventory
- [x] `./gradlew build` + `runGameTestServer` green, review, `mdocs validate`

## Progress Log
- [2026-10-14T05:54:12.293Z] Created initiative via mdocs command
- [2026-10-14T15:45:00.000Z] Resumed; LM Studio endpoint verified live (GET /v1/models → `prism-ml/bonsai-27b` et al.). Plan expanded with SEQ-008 carry-overs (execution loop, currentTask wiring) and guardrails.
- [2026-10-15T00:40:00.000Z] Completed after a builder → coordinator-rescue → reviewer → fix cycle. Delivered: LlmClient (OpenAI-compatible, off-thread + server.execute hop-back, all-failure template fallback, bad_endpoint safe), own .schem v2/v3 + vanilla .nbt parsers (air skipped — real exports are mostly air; hostile-input hardened: 512k volume cap, bounded NBT accounter, BlueprintParseException everywhere), BlueprintLibrary with import folder (spaces normalized), build queue + `/siasworkshop build` command (level 2), build-card datapack JSON loader, AgentDecisionLoop (per-tick execution pass + interval-gated decisions, throttled re-pathing, no remote placement, release-on-stuck/unplaceable, pool citations incl. FAILED drop, ServerStoppingEvent map cleanup), TaskBoard.release. Review caught: stale-now LLM claims (born-expired leases), static-map leaks, citation staleness. Gametest war stories (all in setup/gametest-notes-1-21-1): GameTest world persists across runs (dedicated run-gametest dir + wipe + seeded serverconfig now in build.gradle), onEachTick pre-fills runAtTickTime (fastutil NPE if called inside a delayed callback), framework barrier frames count as spawnable floors (seeded lightScanRadius=3), production tick handlers race manually-driven tests (poll-on-state + key filter seam + pinned decision interval). `./gradlew build` + `runGameTestServer` 26/26 green (coordinator-rerun).

## Artifacts
- `src/main/java/com/siaws/siawsmod/content/llm/` — LlmClient, LlmPrompts, LlmActions (validated action contract)
- `src/main/java/com/siaws/siawsmod/content/agents/build/` — Blueprint, BlueprintLibrary, SchemParser, StructureNbtParser, BlueprintParseException, BuildCard, BuildCards, BuildQueue, BuildCommand
- `src/main/java/com/siaws/siawsmod/content/agents/AgentDecisionLoop.java` — decision/execution loop; `content/agents/tasks/TaskBoard.java` +release
- `src/main/java/com/siaws/siawsmod/gametest/AgentsLlmGameTest.java` — 10 tests (parsers incl. air-skip + hostile input, stub-HTTP LLM client, board release, light/build end-to-end)
- `config/Config.java` — 12 new knobs (llm.* + agents.* loop); `SiasWorkshop.java` — command/tick/stop listeners; `build.gradle` + `.gitignore` — run-gametest dir, world wipe, seeded serverconfig