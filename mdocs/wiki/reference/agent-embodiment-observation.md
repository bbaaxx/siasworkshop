---
id: "agent-embodiment-observation"
title: "Agent Embodiment, Observation Schema, and Shared Decision Pool"
category: "reference"
created: "2026-10-14"
updated: "2026-10-14"
related_initiatives: ["agent-embodiment-observation--2026-10-13"]
tags: ["design","llm","agents","entities","observation","goals"]
lifecycle: "draft"
knowledge_type: "reference"
confidence: "medium"
source_initiatives: ["agent-embodiment-observation--2026-10-13"]
---

> **Verification caveat (2026-10-14):** compiled from training knowledge — no web
> access in the agent environment. Prior-art details (especially **Modular
> Golems'** current version support and license) must be verified online before
> borrowing anything.

Companions: `reference/llm-minecraft-landscape` (integration patterns),
`reference/local-llm-feasibility` (model sizing — the LLM here is a 4B-class local
model, so it *decides*, it does not *control*).

Scope agreed with the user (2026-10-13/14): agents are player-like characters in
the world; they receive full text visibility of the game situation (world state +
available actions); they make decisions drawing from a **common shared decision
pool** that learns from every agent's attempts; goals center on **player comfort**
and **building things**.

## Pillar 1 — Embodiment: how the agent exists in the world

| Option | What it is | Pros | Cons |
| --- | --- | --- | --- |
| A. Fake ServerPlayer | `ServerPlayer` subclass with a fake network connection (Fabric Carpet `/player` is the canonical reference; NeoForge has `FakePlayerFactory` for limited cases) | Full player semantics: inventory, item use, menus, XP, everything gated on `instanceof Player` works | Heavyweight per agent; connection shim is fragile across versions; tab-list/player-slot pollution; non-humanoid shapes fight the player renderer |
| B. Custom Mob (Modular Golems style) | `PathfinderMob` subclasses driven by vanilla Goals + `PathNavigation`, custom models (humanoid, wolf, …) via GeckoLib | Lightweight — many agents cheap; render freedom; fits "construct" fantasy; no networking hacks | Player-gated mechanics fail (some interactions check `instanceof Player`); needs custom inventory/equipment code |
| C. **Hybrid (recommended)** | Custom Mob as the body + a NeoForge `FakePlayer` delegate used only to *perform* interactions (break/place/use) so player-gated code paths still work | Best of both: cheap entity, full interaction fidelity | Two-object bookkeeping; must keep delegate position/inventory in sync |

**Prior art:**
- **Modular Golems** (lcy0x1) — the user's reference: golems assembled from parts,
  humanoid / dog / metal forms, owner + follow/guard/wander modes, held items and
  upgrades. Confirms Option B/C is proven in the ecosystem. **License and 1.21.1
  NeoForge support unverified — treat as structure-only reference until checked.**
- **Fabric Carpet** (MIT) fake-player source — the best code-level reference for
  Option A; study, re-implement, don't copy wholesale.
- **MCA Reborn** (GPL-3.0 — look, don't lift) and **Minecolonies** (custom
  license — inspiration only) for villager/NPC daily-schedule and inventory
  patterns.
- **GeckoLib** (MIT, already in `reference/ecosystem-map`) for humanoid/wolf
  models + animations.

**Key architectural rule (from the feasibility page):** the LLM is never the
low-level controller. Entities execute vanilla-style Goals (`MoveToBlock`,
`PathNavigation`, break/place progress) — the LLM only picks *what* to do at
decision points. A 4B model cannot steer per-tick and doesn't need to.

## Pillar 2 — Observation: the game situation as text

Produced server-side on the server thread (cheap snapshot), consumed off-thread by
the LLM call. Budget: **≤ ~600–900 tokens** per observation for 4B-class models.

Sections (in salience order, compress bottom-up when over budget):

1. **Self** — name, form, position (village-relative), health, held item,
   inventory summary (`oak_log×24, iron_pickaxe(dmg 40%), bread×3`), current task.
2. **Village context** — our foundation-block data: bed count vs population,
   workstations, bell, stockpiles (chest aggregates), recent chronicle events.
3. **Players** — names, distance, health/hunger, what they're holding, current
   shelter status (see comfort metrics below).
4. **Nearby blocks** — immediate 5×5×5 as needed detail; 16-block radius as
   *counts by type* (`log×31, stone×80, torch×2`), never raw lists; explicit POI
   list (beds, chests, workstations, torches, hostiles-spawnable dark spots).
5. **Nearby entities** — players, our other agents (with their current task),
   villagers, hostiles (with distance).
6. **Environment** — time, weather, light level at agent and at nearest player.
7. **Available actions** — the enumerated action space, pre-filtered by
   capability and context (see below). This is what "things available for them to
   do" means concretely.

**Action space (the contract):** `move_to`, `follow`, `gather(block_type)`,
`chop`, `mine`, `craft(item)` (only when station + materials present), `place`,
`build_step(blueprint_id)`, `deposit_to(chest)`, `withdraw_from(chest)`,
`light_area`, `use(item)`, `say(player, msg)`, `rest`, `claim_task(board)`.
Every action is validated server-side against real state before execution — raw
model output never touches the world. Deltas matter: send "changed since last
observation" when possible to stay inside the token budget.

**Cadence:** observe at decision points (idle, task complete/failed, interrupt
event like night-fall or player hurt) — not per tick. Expected rate: roughly one
LLM decision every 20–120 s of gameplay per agent.

## Pillar 3 — Shared decision pool (collective learning)

A **server-wide, per-world store of experience** that every agent reads and
writes — Voyager's skill library, but shared across agents and persisted.

**Entry shape:**
```
{ id, tags: [goal, biome, key-blocks, action-types],
  situation_summary: "one line, LLM-written",
  plan: [validated action sequence],
  outcome: success | fail(reason), metrics: { duration_s, items_delta },
  uses, successes, last_used, schema_version }
```

- **Write path:** after each task, a *deterministic* outcome evaluator decides
  success (did the item get crafted? is the wall placed? did the comfort metric
  improve?). Successes of novel plans become reusable skills; failures are stored
  as anti-patterns with the reason — both teach.
- **Read path:** at decision time, retrieve top-K (K = 2–4, small models!) entries
  by tag/rule match first ("what worked for lighting an area at night"). Optional
  v2: local embeddings via the same Ollama instance (nomic-embed-text class) for
  semantic similarity.
- **Storage:** `SavedData` + JSON mirror in the world folder; versioned schema;
  size-capped with LRU/decay; a skill only becomes "trusted" after N≥3 successes.
- **Safety:** pool entries can only contain actions from the validated action
  space — the pool can never inject arbitrary model text into the world.
  Player-visible audit: pool inspectable via GUI on the foundation block.
- **Thematic bonus:** pool learnings feed the chronicle/gossip system from the
  feasibility page ("the golems learned that torches keep the night safe").

## Pillar 4 — Goals: player comfort + build build build

**Goal model:** a prioritized, shared **task board** (claim/complete protocol so
agents don't duplicate work; claims carry a **lease/timeout** so a crashed or
stalled agent releases the task automatically, and comfort detectors raise a
single shared deficit entry — not per-agent — so two agents can't claim the same
fix). Comfort preempts build. Two goal families:

1. **Ensure player comfort** — deterministic deficit detectors, not LLM guesses:
   - shelter: enclosed space + bed reachable for each player at night
   - light: spawnable-dark spots within village radius / near players
   - food: edible stock in accessible chests above threshold
   - safety: hostiles within radius → escort/fight/flee policy
   Agents claim comfort tasks (place torches, wall a gap, stock food). The LLM
   picks *how* from the action space; the detectors decide *that* it's needed.
2. **Build things** — a build queue. **Builders never build randomly**: every
   build task references a concrete *blueprint* from the build-knowledge sources
   below, with materials, footprint, and step order known up front.
   - v1: template structures (we already speak template pools/structure processors
     from the cherry-village work) + player-imported schematic files; player
     queues a blueprint via GUI on the foundation block; agents gather materials
     (withdraw/gather) then `build_step` through the plan.
   - v2: LLM-adapted blueprints — the LLM selects a blueprint from the library
     and adapts it (material substitution to what's in stock, palette swap to
     village style, size variant) with the player approving from a shortlist
     (trust ladder: propose → approve → eventually auto-approve small builds).

### Build knowledge: where blueprints come from

The user requirement: "agents should have access via the LLM (or the LLM should
have access) to online content describing Minecraft builds." A 4B-class local
model **cannot browse the web** — so the access pattern is *retrieve-and-inject*:
the mod (or dev-time tooling) fetches/distills build content into a local
library, and at decision time the retriever injects the relevant entries into
the LLM prompt. The LLM selects and adapts; it never fetches.

| Source | Form | How agents use it | Licensing posture |
| --- | --- | --- | --- |
| Our own templates | Vanilla structure NBT / template pools (cherry-village work) | Direct `build_step` execution | Ours — ship freely |
| Player-imported files | Sponge `.schem` (WorldEdit), `.litematic`, vanilla `.nbt` dropped in a config folder | Parsed locally into blueprint tasks (BYO library, like a resource pack) | Clean: user supplies their own files; we never redistribute |
| Community schematic sites (minecraft-schematics, GrabCraft, Planet Minecraft) | `.schem` / `.schematic` / `.mcstructure` downloads | **Not** fetched live by the mod — users download and import via the folder above | Uploads are creators' copyright with mixed terms; redistributing bundled copies in an ARR mod is not safe |
| Online build guides / wiki / video tutorials | Text: dimensions, materials lists, palettes, proportions, style tags | Dev-time distillation into "build cards" (see below) | Facts (block counts, proportions) are fine; don't copy prose wholesale |
| Live web search / scraping at runtime | — | **Rejected**: fragile ToS, no official APIs, and pointless with a local 4B model | — |

**Build cards (the RAG corpus):** structured records, one per build idea —
`{ name, style_tags, size, materials_list, palette, step_summary, source_note }`.
Produced at dev time (a big hosted LLM can do the distillation of public guides;
that's an offline tooling step, not a shipped feature) and/or written by us and
by players. Distillation keeps **facts** (block counts, dimensions, palettes) and
re-expresses procedure in our own words and structure — never copies prose or
step-by-step text wholesale. Stored as datapack-style JSON so packs can add more — fits our
datagen conventions. At decision time the retriever pulls 1–3 relevant cards
into the prompt ("the library has: watchtower 7×7×18, ~400 stone bricks, …"),
the LLM picks one and adapts materials to actual stockpiles.

**Parsing formats we implement ourselves** (publicly documented or de-facto
documented formats — vanilla structure `.nbt` has no formal spec): Sponge
Schematic v2/v3 (`.schem`), Litematica (`.litematic`), vanilla structure `.nbt`.
Bedrock `.mcstructure` is out of scope. Write our own parsers — do not copy
WorldEdit (GPL) or Litematica (LGPL) parser code.

**Grief guardrails (non-negotiable):** agents break/place only inside village
bounds + player-approved plots; block-type whitelist per task; never break
player-built blocks unless the task explicitly says so; every agent action is
logged to the chronicle.

## Measured budget (2026-10-14, user live test)

Transcribed chat sample ≈ 450 tokens; full snapshot ≈ 3× that → **~1200–1500
observed vs ≤600–900 target** for 4B-class models. Confirmed noise: per-slot
inventory dumps instead of counts, repetitive POI lines, verbose action names.
Compression pass (caps + compact POI format + short action names + budget
gametest) is in scope of `agents-v1-decision-pool-goals`. Debug tooling note:
the Agent Inspector also writes the snapshot verbatim to a file on disk — chat
transcription is lossy.

## Recommended v1 slice

1. One humanoid agent entity (Option C hybrid, GeckoLib model) bound to a village
   foundation block; wolf form as a cosmetic variant later.
2. Observation snapshot (sections 1–7) + ~12 validated actions.
3. Shared decision pool with tag-based retrieval (embeddings in v2).
4. Two comfort detectors (light, shelter) + build queue over our own templates
   and player-imported `.schem`/`.nbt` files; build-card corpus as datapack JSON.
5. Everything config-gated, template fallback when no LLM endpoint — per
   `reference/local-llm-feasibility`.

Suggested repo shape when implemented: `content/agents/` (entity, goals,
observation, pool, task board) + `content/llm/` (endpoint client), registration
holders in `init/` per AGENTS.md.

## Referenced By

*Auto-generated by mdocs*

- agent-embodiment-observation--2026-10-13
