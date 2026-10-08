---
id: "cherryfy-villages"
title: "Cherryfying Villages: Structure Processor + Config Gate"
category: "worldgen"
created: "2026-10-08"
updated: "2026-10-08"
related_initiatives: ["cherryfy-villages", "cherry-village-worldgen"]
tags: ["worldgen","villages","structure-processor","template-pools","config","cherry"]
lifecycle: "stable"
knowledge_type: "how-to"
confidence: "high"
source_initiatives: ["cherryfy-villages"]
---

## Behavior as shipped

- **Default (config off):** villages generate exactly as vanilla — and also in cherry groves, via the biome tag injection in [village-generation-notes](village-generation-notes.md).
- **`cherryfyVillages = true`** (SERVER config, per-world, default false): plains-style villages generate with cherry as the primary wood; each building's accent wood is re-drawn from birch/jungle/acacia. Only newly generated chunks are affected; the toggle is read live during worldgen, so flipping it mid-game works for fresh chunks.

## Architecture

- `CherryfyProcessor` (a `StructureProcessor`) with a fully parametric codec: `primary` (default `oak`), `base` (default `cherry`), `sources` (accent-candidate woods that get re-drawn), `accents` (the pool to draw from). All knobs are settable per processor-list JSON.
- `WoodFamily` table: 6 families (oak, cherry, birch, jungle, acacia, spruce, dark_oak) x 18 member kinds (log, wood, stripped variants, planks, stairs, slab, fence, fence_gate, door, trapdoor, pressure_plate, button, sign, wall_sign, hanging_sign, wall_hanging_sign, leaves, sapling). `remap` copies `BlockState` property values by name, so log axis, door hinge/half, stair shape, etc. survive.
- Per-piece accent: seed = hash of `StructurePlaceSettings.getBoundingBox()` min corner (fallback: coarse world-pos cell), so every block in one building agrees and neighboring buildings differ.
- Pool overrides: vanilla `template_pool` JSONs copied to `src/main/resources/data/minecraft/worldgen/template_pool/village/plains/`, with each element's `processors` re-pointed to a **composed** list under `data/siasworkshop/worldgen/processor_list/` that runs the vanilla processors first and appends `siasworkshop:cherryfy` last. Never replace a vanilla processor list — compose it, or mossify/zombie/street decay effects silently disappear.
- Config gate: `processBlock` returns the input unchanged when `Config.CHERRYFY_VILLAGES` is false. Because the vanilla processors still run, config-off output is byte-identical to vanilla.

## Gotchas (hard-won)

- Vanilla `processors` fields come in **three JSON shapes** — bare string id, inline array, or `{"processors": [...]}` object. Plains vanilla uses all three; any generator script must handle each.
- `DeferredRegister.register(name, CherryfyProcessor::type)` must use an explicitly-typed SAM factory method (`public static StructureProcessorType<CherryfyProcessor> type() { return () -> CODEC; }`). An inline lambda `() -> CODEC` fails compilation on wildcard capture.
- **Feature pool elements bypass processors.** The decor tree (`feature_pool_element`) cannot be gated or remapped by a processor — the oak→cherry decor swap was reverted to vanilla oak for this reason (tester decision: oak decor tree inside cherry groves is fine). Vanilla plains decor has exactly one tree slot (weight 1) — one tree per village is vanilla design, not a bug.
- Vanilla reference data for overrides: `unzip build/moddev/artifacts/neoforge-21.1.256-client-extra-aka-minecraft-resources.jar 'data/minecraft/worldgen/*'` (jar ships the full vanilla datapack). `/tmp` may be wiped between sessions — re-extract as needed.
- Server configs load before worldgen, so `Config.CHERRYFY_VILLAGES.getAsBoolean()` inside `processBlock` is safe. Datagen never calls `processBlock`.

## Verification recipe

1. `./gradlew runClient`, cheats-enabled creative world.
2. Config off: `/place structure minecraft:village_plains` → vanilla oak village. Config on (edit `run/saves/<world>/serverconfig/siasworkshop-server.toml`, or Mods → Sia's Workshop → Config in-world) → cherry village with per-house accent variety.
3. Natural spawn in cherry groves: single-biome cherry grove world + `/locate structure minecraft:village_plains` (see village-generation-notes).

## Roll-out status

- **plains: done and verified** in both modes.
- **savanna/snowy/taiga: pending.** Needs per-style processor lists (e.g. `cherryfy_savanna` with `primary: "acacia"`, snowy/taiga `primary: "spruce"`) — the processor only remaps the *primary* family to cherry, so a style whose primary is left at default oak would see its native wood randomized instead.
- **desert: undecided** — sandstone has no cherry equivalent; current mapping only touches wood-family blocks, which desert barely uses, so desert villages are effectively unchanged even with the option on.

## Mod-conflict note

Overriding `minecraft:` pool paths shadows vanilla for every datapack — another mod overriding the same plains pools will conflict (last one wins). Accepted for now because the feature is opt-in; the conflict-free alternative documented in village-generation-notes (a fully custom `siasworkshop:village_cherry` structure) remains available if this ever needs to default on.

## Referenced By

*Auto-generated by mdocs*
