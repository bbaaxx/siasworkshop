---
id: "agents-action-surface-expansion-hands-materials-economy-roadmap"
title: "Agents action-surface expansion: hands, materials, economy (roadmap)"
status: "active"
created: "2026-10-23"
updated: "2026-10-23"
owner: ""
tags: ["agents", "roadmap", "actions", "knowledge", "mcp"]
related_wiki: ["reference/agent-knowledge-system", "reference/mcp-pattern-tree-design", "setup/gametest-notes-1-21-1", "setup/session-handoff-2026-10-26"]
---

## Objective

Greatly and vastly increase the surface of actions agents can execute in-game
(user direction, 2026-10-22 — "as we expand the surface of actions we would want
the agent to get the materials from any of the available means our surface would
provide such as gathering, harvesting, mining, trading, even searching").

Design constraints carried forward (SEQ-016 autonomy north star + pillar 4):
**Java executes; the model decides.** New capabilities land as mod-side
executors + board task types, surfaced to the model through the MCP catalog
(reference/mcp-pattern-tree-design). The knowledge ledger
(reference/agent-knowledge-system) is the shared substrate: every new capability
adds resolution paths for parked deficits AND records what agents learn.

## Current surface (baseline, 2026-10-23)

- LLM action space (LlmActions): `claim`, `rest`, `say`, `build(blueprint)`.
- Executors: COMFORT_LIGHT, COMFORT_SHELTER, BUILD (staging + extrication +
  materials ledger parking). Agents are PLACES-ONLY — they never break blocks.
- Materials: `withdrawOnce` scans chests near an anchor (radius 16); no deposit,
  no wide-area logistics.
- Observation already ADVERTISES actions with no real executor behind them:
  `craft(item)`, `place(block)`, `deposit(chest)`, `withdraw(chest)` — gated by
  proximity only. These are the cheapest expansion slots.
- MCP catalog (S0): observe_self, observe_board, claim, rest, say.

## Roadmap (phases, each shippable + live-testable)

### P0 — Hands and logistics (foundation for everything else)

1. **Block breaking with guardrails** — breaks the "places-only" pillar.
   Guardrails mirror placements: break-whitelist per task (blueprint obstruction
   cells, log/leaf for gather, ore for mine), stationRadius/guardRadius bounds,
   tool requirement for hard blocks (no bare-hand stone), drops either to agent
   inventory or picked up. Kills the `obstructed` terminal-fail class at the
   same time (the mod can now clear leaves/rubble it would previously give up
   on — the live 2026-10-20 leaves episode).
2. **Deposit/withdraw executors** — real backing for the advertised actions:
   deposit inventory overflow to station chests; withdraw materials from any
   stocked chest in the network (not just near one anchor). Turns the agent
   into a logistics actor and de-risks long builds.

### P1 — Material self-sufficiency (closes the ledger loop)

3. **Gather** — punch trees/leaves, collect drops; forage loose items.
   Task type raised by the ledger (a blocked build needing planks → gather job).
4. **Mine** — mine to deficit: ledger entries with no source become MINE tasks
   targeting known ore/stone near the station; agent crafts/obtains tools,
   mines, deposits. FIRST full supply-side ledger resolution.
5. **Craft** — crafting-table interaction (FakePlayer/container based):
   planks → sticks → tools → torches; recipe knowledge recorded in the ledger
   (RECIPE_LEARNED entries).
6. **Farm** — till/plant/harvest/replant near the station; food security so
   agents don't depend on player-supplied bread.

### P2 — Economy and search

7. **Trade** — villager interaction (the mod's village theme; cherryfied
   villages are right there): buy materials with emeralds, sell surplus;
   trade prices/routes recorded in the ledger.
8. **Search/scout** — explore radius around the station; record POIs (caves,
   ore veins, wild crops, villages) as SOURCE_DISCOVERED ledger entries —
   the planner's input the knowledge system was designed for.

### P3 — Decision-layer surface (parallel track, per pattern-tree design)

9. MCP tool catalog growth: observe_inventory, and effect tools for the new
   capabilities; in-mod schema validation stays the gate; card-based expansion
   ({result, children}) as capabilities land.
10. Multi-step workflows: the S2 walk (think → analyze → design → materials →
    blueprint → apply) becomes real on the grown surface.

### P4 — Knowledge system growth (spine across all phases)

11. New AgentKnowledge entry types as phases land: SOURCE_DISCOVERED,
    RECIPE_LEARNED, STOCK_LEVELS, TRADE_ROUTE. Retrieval injects relevant
    knowledge at decide time (the "shared knowledge system" the user asked for).

## Resume here (for the next agent)

Canonical session state lives in `setup/session-handoff-2026-10-26`
(reading order, resume order, traps, environment facts). Next slice: **P1
gather + craft primitives** — shapes mirror slice 1 (primitive + guardrails +
teaching rejects + MCP effect tool + sidecar catalog entry + gametests in the
`agentsBuildStaging` batch with try/finally discards + dirt platforms +
simulated walks). See "User direction 2026-10-23" — the LLM composes; the mod
validates and executes.

## Slice 1 progress log (breaking primitive — 2026-10-23)

- User scoping: first slice = breaking + guardrails; guarded breaking (radius +
  tool-tier + protected structures); NO preset tool supply — the LLM composes
  primitives toward goals (needs-driven means-ends reasoning; see "User
  direction" above).
- SHIPPED `BreakSupport` (`content/agents/primitives/`): evaluate/breakBlock;
  guardrails = config gate (`agents.breaking.enabled`), air/unbreakable/
  too_far/protected (block entities + beds) refusals, vanilla Tool-component
  correctness for NEEDS_*_TOOL blocks with teaching reason
  `needs_tool:<tier>_<kind>`, auto-equip of a suitable tool from the inventory,
  drops into the agent inventory (overflow at feet), tool durability.
- BUILD executor clears obstructions via the primitive before the 20-cycle
  give-up (live 2026-10-20 leaves episode class is gone when tooling allows).
- MCP surface: `break_block` effect tool (agent, pos) — read-only debug
  transport unaffected (category-gated); sidecar S0 catalog + generic agent-arg
  injection + native prompt updated, so the model can compose the primitive
  today. Observation already exposes held/inv for the means-ends reasoning.
- Gametests ×4 (batch agentsBuildStaging): guardrail reasons, drops + equip +
  durability, BUILD obstruction clearing end-to-end, MCP tool act/teach rejects.
- VERIFIED 2026-10-25: 55/55 gametests, python suite green (bridge/tap/
  tap-import/corpus/decision-probe/calibrate + S0 replay 20/20), mdocs clean,
  ./gradlew build green. Debug lessons → setup/gametest-notes-1-21-1: (1)
  `NEEDS_STONE_TOOL` is an ORES-only tag — stone itself is hand-breakable for
  the primitive (drops still need the tool via loot conditions); (2) the
  try/finally agent-discard rule — a pre-discard assert failure leaks the
  agent into later batches (symptoms shift run-to-run: shelter roof missing,
  light-claim contested); (3) tier0 native instrumentation keeps the
  board-decision set {claim, rest, say} — break_block is production surface,
  not a tier0 measured axis.
- LIVE TEST 2026-10-27 (TestSidecar01, in vivo): ledger full loop validated
  (park → restock → live quantity refresh x27→x21 → resolve → re-claim);
  obstruction clearing validated (two mazons builds DONE with leaves in their
  footprints; zero obstructed failures where 10-22 guaranteed them); dual-
  agent cycling with clean timeout fallback. Clearing was invisible in logs →
  shipped a `cleared obstruction` INFO line. Model-composed break_block: zero
  calls (expected — no gathering goals yet; the P1 motivation gap in action).
  Detail: setup/session-handoff-2026-10-26 + ledger SEQ-038.

## Status

- P0 slice chosen with user input; P0.1 (breaking) or P0.2 (logistics) first
  depending on the answer. Each phase = its own verified slice: gametests +
  python suite + mdocs validate + (user) live test before the next.

## User direction 2026-10-23 (shapes the whole roadmap — slice 1 design input)

Tool/material sourcing is NOT codified as a preset path. The **need** for a
tool arises from an agent's goal (e.g. "food must be gathered"), and the
**means-ends chain is the LLM's reasoning over composable primitives**: farm
→ needs a hoe → logs → planks → sticks → hoe → needs seeds → break grass → …
The mod provides validated primitive effects (break, gather, craft, till,
plant, …) with guardrails and teaching rejects; the model composes them
toward goals. Consequences: (1) new capabilities land as LLM-callable
primitives (MCP effect tools), not only as executor internals; (2) the
break primitive (slice 1) is agent-relative and goal-agnostic — whitelisted
per-context, tool-tier gated, never player-protected structures; (3)
observation must expose inventory/tools so the model can reason about means;
(4) the ledger grows recipe/tool knowledge (later phases) so retrieval feeds
the reasoning.
