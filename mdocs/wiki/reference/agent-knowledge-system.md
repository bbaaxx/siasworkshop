---
id: "agent-knowledge-system"
title: "Agent Knowledge System (in-game shared ledger)"
category: "reference"
created: "2026-10-22"
updated: "2026-10-22"
related_initiatives: ["build-executor-self-entombment-station-material-gaps-live-test-2026-10-22"]
tags: ["agents","knowledge","ledger","build","materials","design"]
lifecycle: "evolving"
knowledge_type: "design"
confidence: "high"
source_initiatives: ["build-executor-self-entombment-station-material-gaps-live-test-2026-10-22"]
---

The in-game shared knowledge store for workshop agents. Born from the 2026-10-22
live-test postmortem (three mazons cubes died three different ways — see the
initiative) and from a user direction that reframed task failure semantics:

> Agents never "await" the player. A task the agents cannot complete with the
> currently available means is **discarded into the ledger** — recorded, parked,
> and re-activated automatically when the means appear (today: the player
> restocks a station chest; tomorrow: an agent gathers, harvests, mines, trades,
> or searches for it). Until then the entry IS the knowledge: "we need N of X at
> place P, and no known source exists".

## v1: `AgentKnowledge` SavedData (`content/agents/knowledge/`)

Per-level like `TaskBoard`/`AgentDecisionPool`
(`siasworkshop_agent_knowledge.dat`), reached via
`AgentWorldData.knowledge(level)`.

- **Entry type: `MaterialNeed`** — one per (task, item): taskId/key, blueprint,
  anchor, itemId, quantityNeeded, firstBlockedAt, lastCheckedAt, status.
  Entries are schema-versioned (unknown versions skipped on load) — new entry
  types slot in beside it, which is how this store grows into the full
  knowledge system.
- **Status lifecycle:** `BLOCKED` → `RESOLVED_RESTOCKED` (station chests within
  `agents.stationRadius` of the anchor hold the remaining quantity) |
  `RESOLVED_PLACED` (nothing left to place) | `RESOLVED_GONE` (task no longer
  active). `quantityNeeded` is refreshed at every re-check against the live
  world.
- **Who blocks:** `executeBuild` on a materials shortfall (previously
  `missing_material` terminal-fail — that stranded partial structures, the
  visible "defective cube"). Records the deficit, then a transient release.
- **Who unblocks:** `recheckBlocked`, run from `onServerTick` every
  `agents.knowledgeRecheckSeconds` (default 60 s). A task unblocks only when
  EVERY recorded item is satisfied (or fully placed) — one short item keeps
  the whole task parked.
- **Claimability:** `openTasks()` (decide path, template fallback, MCP surface)
  filters blocked task ids; `McpTools.claim` rejects them with an explicit
  reason; `observe_board` lists them separately in `blocked_tasks` so the model
  (and the player) see the ledger rather than a silently missing task.

## Why not "awaiting"

An explicit waiting state would (a) burn decision cycles, (b) hard-code the
player as the supply chain. The ledger inverts it: the deficit is durable
knowledge, the agent walks away, and ANY future supply surface — player
restock, agent mining, trading — resolves the same entry. An entry with no
known source is precisely the input a future planner reads to choose which
capability to develop.

## Interactions and known warts

- **Release cooldown vs unblock:** the executor's 6000-tick release cooldown
  (`RELEASE_COOLDOWN_TICKS`) is independent of the ledger. After an unblock the
  same agent may need up to ~5 game-minutes before it can reclaim the task.
  Harmless today (single-agent villages); revisit when multi-agent work
  distribution lands.
- **Availability check is chests-only** within `agents.stationRadius` of the
  anchor (mirrors `withdrawOnce`). Agent-held items are not counted — a
  restocked agent mid-elsewhere won't unblock a task.
- **Cap:** 256 entries; oldest resolved drop first, then oldest blocked.
- **Startup entombment** (agent saved inside a sealed cube from a previous
  session) is handled by the existing suffocation bail, not the ledger.

## The 2026-10-22 build-executor fixes this store accompanies

1. **Staging cell** (`nearestStagingCell`, public): builds are executed from
   the nearest standable cell to the anchor OUTSIDE the remaining footprint —
   never from the anchor corner (unstandable cliff tops) and never from inside
   the blueprint (self-entombment: the agent skipped its own cells forever and
   the build could never complete). `staging == null` → `unroutable` release,
   three strikes terminal-fail (`TaskBoard.UNREACHABLE_RELEASE_THRESHOLD`).
2. **Materials shortfall → ledger block** (above), replacing the
   `missing_material` terminal fail.
3. The entomb guard (`placementEntombsAgent`) stays as belt-and-suspenders; the
   staging cell makes its "agent drifts off (random stroll)" assumption
   unnecessary.

Gametest pins: `buildExtricatesItselfAndCompletesFootprint`,
`buildStagesOnGroundBelowFloatingAnchor`,
`materialsShortParksTaskInKnowledgeLedger` (AgentsExecutorGameTest).
