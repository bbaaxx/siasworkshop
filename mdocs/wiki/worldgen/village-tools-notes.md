---
id: "village-tools-notes"
title: "Village Tools Notes: Compass, Foundation Block, Structure Lookups"
category: "worldgen"
created: "2026-10-08"
updated: "2026-10-08"
related_initiatives: ["village-tools-wilderness-compass-village-foundation-block"]
tags: ["items","blocks","gui","structure-lookup","compass","villages"]
lifecycle: "stable"
knowledge_type: "how-to"
confidence: "high"
source_initiatives: ["village-tools-wilderness-compass-village-foundation-block"]
---

## What shipped

- **Wilderness Compass** (`wilderness_compass`): right-click scans for the nearest village (`StructureTags.VILLAGE`), stores it in the `siasworkshop:village_target` data component, needle points away. Scan message includes a build/no-build verdict vs 128-block separation.
- **Village Foundation** (`village_foundation`): block + BlockEntity + Menu + Screen. GUI has a flavor cycle (Cherry Blossom / Vanilla) and a Create button. Cherry flavor forces the cherryfy processor via `CherryfyProcessor.force` (ThreadLocal) regardless of server config. After success the BE's `generated` flag makes it inert.
- Reference pages: [cherryfy-villages](cherryfy-villages.md), [village-generation-notes](village-generation-notes.md).

## The compass trick worth reusing

Subclass vanilla `CompassItemPropertyFunction` and pass a `CompassTarget` that mirrors the entity through the stored target: `T = 2E - V`. Vanilla needle math then points exactly away from V — wobble, spin-when-no-target, and 16-sprite rendering all come free. Register with `ItemProperties.register(item, ResourceLocation.fromNamespaceAndPath(modid, "angle"), fn)` in `FMLClientSetupEvent.enqueueWork`; hand-write the item model JSON with the vanilla compass override table (predicate swapped to `siasworkshop:angle`, sprites borrowed from `minecraft:item/compass*`) — compass override tables are too fiddly for datagen. Store the target at the player's Y so the mirror is purely horizontal.

## Structure lookup gotchas (all verified on 21.1.256)

- **`ServerLevel.findNearestMapStructure(tag, pos, radius, skip)` radius is in SECTIONS (×16), not blocks.** Passing 128 means 2048 blocks. Convert: `(blocks + 15) / 16`.
- **Returned positions have a synthetic Y** (concentric rings y=32, random-spread potentials y=0) and internal comparisons are 3D. Never show that distance to a user and never threshold on it — use horizontal distance or you get a floor at local terrain height (`VillagePlacer.horizontalDistance`).
- **`StructureStart.placeInChunk` never registers the start.** Villages placed via `/place structure` or by hand-placed code are invisible to `checkStructurePresence` and `/locate` until registered: `structureManager.setStartForStructure(SectionPos.of(start.getChunkPos(), 0), start.getStructure(), start, chunk)` with the chunk fetched at `ChunkStatus.STRUCTURE_STARTS` (note package: `world.level.chunk.status`). Natural worldgen does this in `ChunkGenerator.createStructures`; do it after any manual placement or your separation checks go blind to your own structures.

## Manual structure placement (VillagePlacer)

Mirror `PlaceCommand.placeStructure`: `structure.generate(registryAccess, generator, generator.getBiomeSource(), randomState, structureManager, seed, new ChunkPos(pos), 0, level, biome -> true)` → validity check → force-load every chunk in the start's bounding box (`level.getChunk(x, z)`) → `start.placeInChunk(...)` per chunk with full-height chunk boxes. Villagers and chests spawn because this is the exact command code path.

## GUI without custom packets (first menu/screen pattern)

- `MenuType` via `IMenuTypeExtension.create(MyMenu::fromNetwork)`; server sends the BlockPos by opening with `player.openMenu(be, pos)` (IPlayerExtension default writes the pos into the extra-data buffer).
- `MenuScreens.register` is **private in 1.21.1** — use NeoForge's `RegisterMenuScreensEvent` (mod bus, client).
- Screen buttons → `minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id)` → server `menu.clickMenuButton(player, id)`. That is the entire networking layer for button UIs; sync state back with a `SimpleContainerData` + `addDataSlots`.
- `BlockEntityType.Builder.of(...).build(null)` (the `Type<?>` param is DFU-only; vanilla passes null). NBT: 1.21.1 uses `tag.getString/getBoolean` — `getStringOr/getBooleanOr` don't exist until 1.21.2.

## Verification recipe

1. Compass: scan near a village (small horizontal distance + "too close" verdict) → walk away → "clear to build" at ≥128 blocks. Needle always points away.
2. Foundation: refuse within 128 blocks of any registered village, accept beyond; second Create on the same block → "already created"; `/locate structure #minecraft:village` finds block-placed villages (proves start registration).
3. Cherry flavor while `cherryfyVillages` config is off → still cherry (ThreadLocal force); Vanilla flavor → respects config.

## Follow-ups

- Recipes for both items (currently creative-tab only).
- Style picker in the GUI (plains/snowy/...), mirroring the per-style `primary` processor lists noted in cherryfy-villages.
- Villages placed before the start-registration fix are unregistered in existing worlds (only affects old test worlds).
