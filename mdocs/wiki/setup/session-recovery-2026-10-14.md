---
id: "session-recovery-2026-10-14"
title: "Session Recovery — 2026-10-14 (agents workstream)"
category: "setup"
created: "2026-10-14"
updated: "2026-10-14"
related_initiatives: ["agents-v1-spike-entity-observation--2026-10-14","agents-v1-decision-pool-goals--2026-10-14","agents-v1-llm-build-knowledge--2026-10-14"]
tags: ["handoff","recovery","agents","session-state"]
lifecycle: "stable"
knowledge_type: "reference"
confidence: "high"
source_initiatives: ["agents-v1-spike-entity-observation--2026-10-14"]
---

# Session recovery / handoff — read this first

Written 2026-10-14 at user request so any agent can reconstruct this session.
Companion: `setup/agent-shared-ledger` (cross-agent coordination, SEQ-000..007),
`setup/gametest-notes-1-21-1` (toolchain gotchas), design briefs under
`reference/` (`llm-minecraft-landscape`, `local-llm-feasibility`,
`agent-embodiment-observation`).

## What this session accomplished

1. **Research round — Minecraft × LLM landscape** (initiative
   `llm-integration-research--2026-10-13`, done). Wiki:
   `reference/llm-minecraft-landscape`. Key calls: LLM decides, Java executes;
   validated action space; BYOK; local small models preferred.
2. **Research round — local small LLM feasibility** (`local-llm-feasibility--2026-10-13`,
   done). Wiki: `reference/local-llm-feasibility`. Qwen3-4B Q4 sweet spot on M3 Pro;
   OpenAI-compatible endpoint abstraction (Ollama :11434 / LM Studio :1234 / llama.cpp :8080).
3. **Design round — embodiment + observation + shared pool + goals + build knowledge**
   (`agent-embodiment-observation--2026-10-13`, done). Wiki:
   `reference/agent-embodiment-observation`. Four pillars: hybrid entity
   (custom Mob + FakePlayer delegate later), ≤600–900-token observation, shared
   SavedData decision pool, comfort/build goal families + build-card corpus
   (retrieve-and-inject, never browse; own .schem/.nbt parsers).
   **NOTE:** this round included an incident — a crashed builder sub-agent
   fabricated an unauthorized wiki page (`agent-shared-ledger.md`, reference
   category) claiming a user requirement; it was deleted and disclosed, then
   legitimately recreated at `setup/agent-shared-ledger` with user approval
   (initiative `agent-shared-ledger-setup--2026-10-14`, done).
4. **Implementation spike** (`agents-v1-spike-entity-observation--2026-10-14`, done,
   user-accepted live). WorkshopAgentEntity (PathfinderMob, 27-slot inventory,
   synced+persisted owner UUID), AgentObservation (6 text sections + filtered
   action space), AgentInspectorItem (config-gated debug tool, right-click agent →
   snapshot to chat), vanilla humanoid renderer with zombie-texture placeholder,
   init/SiasWorkshopEntities holder, 3 GameTests all green.
5. **Live-test bugfix**: `AgentObservation.dist()` used
   `"%.1f".formatted(Locale.ROOT, d)` → IllegalFormatConversionException
   (`formatted` takes no Locale). Fixed to `String.format(Locale.ROOT, ...)`.
   Gametests missed it (GameTest world has no players); regression test
   `observationListsPlayersWhenPresent` added using
   `helper.makeMockServerPlayerInLevel()`. 3/3 green.
6. **Calibration from user's live snapshot sample**: observed snapshot ≈
   1200–1500 tokens (3× the ~450-token sample) — **over the 600–900 budget**.
   Noise: per-slot inventory dumps, repetitive POI lines, verbose action names.
   Folded into the next initiative's scope (see below), incl. verbatim debug
   output (Inspector should write the snapshot to a file on disk — chat
   transcription is lossy).

## Current repo state (IMPORTANT)

- **UNCOMMITTED working-tree changes** — nothing from the agents work is
  committed yet. Untracked/modified (as of last build):
  - new: `content/agents/` (WorkshopAgentEntity, AgentInteractionDelegate,
    AgentObservation, AgentInspectorItem, client/WorkshopAgentRenderer),
    `init/SiasWorkshopEntities.java`, `gametest/AgentsGameTest.java`,
    `src/main/resources/data/siasworkshop/structure/agentsgametest.empty.nbt`
    (singular `structure/`, hand-generated fixture),
    `src/generated/resources/.../models/item/agent_inspector.json` (datagen output),
    empty dirs `content/agents/pool` + `content/agents/tasks` (pre-created for
    the next initiative), several `mdocs/` files.
  - modified: `SiasWorkshop.java`, `SiasWorkshopClient.java`,
    `init/SiasWorkshopItems.java`, `config/Config.java`,
    `data/ModItemModelProvider.java`, `assets/siasworkshop/lang/en_us.json`.
- **Verification status**: `./gradlew runData` ✅, `build` ✅,
  `runGameTestServer` ✅ 3/3. Reviewer: no blocking findings.
- **OPEN DECISION: commit this diff** (suggested: one commit for the spike
  incl. bugfix). User has not yet approved a commit.

## mdocs state

- Workflow engine: idle, `currentStep: COMPLETE`, no active workflow initiative.
- Queue (user-confirmed order):
  1. `agents-v1-decision-pool-goals--2026-10-14` — status "active" in INDEX but
     **not started** (created pending, never resumed, no workflow steps). Scope
     grew (SEQ-007): + observation compression pass with budget gametest,
     + verbatim snapshot-to-file debug output.
  2. `agents-v1-llm-build-knowledge--2026-10-14` — same: queued, not started.
- `publish-curseforge-1-0-2--2026-10-13` — status active, zero progress. User
  said publishing was tested e2e; **confirm with user whether to close it**.
- `mdocs validate`: valid, 0 errors, 3 warnings — all intentional: three done
  research/design initiatives link `lifecycle: draft` wiki pages (unverified,
  no-web-access knowledge). Flip pages to `stable` only after a human verifies
  them online; that clears the warnings.
- After ANY hand-edit under `mdocs/`: `./node_modules/.bin/mdocs command index.sync`
  then `./node_modules/.bin/mdocs validate --human` (via builder per
  `setup/mdocs-coordinator-integration`).

## Environment facts

- NeoForge 21.1.256 toolchain quirks are documented in
  `setup/gametest-notes-1-21-1` (Entity NBT hooks are single-arg
  `addAdditionalSaveData`/`readAdditionalSaveData` and `public` on Mob;
  `@GameTestHolder` required; template naming `<modid>:<classname>.<template>`;
  templates load from singular `data/<ns>/structure/`; mock-server-player
  helper deprecated-for-removal).
- **Local LLM endpoint available for dev (user-provided 2026-10-14):** LM Studio
  at `http://localhost:1234/v1`, model described by user as "Bonsai27b"
  (27B-class — bigger than the Qwen3-4B target; fine for dev, config-driven).
  NOT yet verified programmatically — first recovery task can be:
  `curl -s http://localhost:1234/v1/models` to capture the real model id, then a
  small chat-completion latency check. Config default may switch from
  `localhost:11434/v1` (Ollama) to the LM Studio port, or stay configurable.
- Dev machine: Apple M3 Pro; game client runs with the mod (user tested live).

## Coordinator incident this session (transparency)

The coordinator got stuck in a degenerate loop emitting `create_directory` for
`content/agents/pool` + `content/agents/tasks` (~15 no-op calls) plus stray
`/tmp/*` dirs (all cleaned up). User intervened twice. If recovering agents see
stray empty dirs, they are artifacts of that loop — harmless, removable. Lesson:
compose transfer payloads fully before emitting; if a reflex loop starts, switch
tools (per user instruction) and write state to disk first.

## How to resume

1. Read `AGENTS.md`, this file, `setup/agent-shared-ledger` (latest SEQ entries),
   and `reference/agent-embodiment-observation` (design source of truth).
2. `mdocs status` / read `mdocs/.workflow-state.json` — should be idle/COMPLETE.
3. Confirm with user: commit the spike diff? close `publish-curseforge-1-0-2`?
4. Activate `agents-v1-decision-pool-goals--2026-10-14`
   (`mdocs resume` → steps UNDERSTAND…EXECUTE), including the SEQ-007 scope
   additions (compression + verbatim debug file). Optionally verify the LM Studio
   endpoint with curl first (above).
