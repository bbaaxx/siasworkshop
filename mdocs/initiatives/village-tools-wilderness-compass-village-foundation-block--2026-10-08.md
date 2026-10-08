---
id: "village-tools-wilderness-compass-village-foundation-block"
title: "Village tools: wilderness compass + village foundation block"
status: "done"
created: "2026-10-08"
updated: "2026-10-08"
owner: ""
tags: ["villages","items","blocks","gui","worldgen"]
related_wiki: ["worldgen/village-tools-notes"]
priority: "medium"
graduated: "2026-10-08"
---

## Objective
Two creative-mode dev tools for village work (recipes come later):
1. **Wilderness Compass** (`wilderness_compass`) — right-click scans for the nearest village and stores its position in a data component; the needle then points AWAY from it, so the player can walk until the minimum village separation is satisfied. Vanilla compass is the rendering template (placeholder art reuses vanilla compass sprites).
2. **Village Foundation** (`village_foundation`) — a block with a small GUI (flavor picker + Create button) that generates a village at its location using the same code path as `/place structure minecraft:village_plains`, gated by the same separation condition. Default flavor is **Cherry Blossom** (forces the cherryfy reskin during placement regardless of the server config); Vanilla flavor respects current config.

## Plan
- [x] Create initiative; verify all 1.21.1 API signatures from the sources jar
- [x] Compass: data component `siasworkshop:village_target` (holder SiasWorkshopDataComponents); WildernessCompassItem.use scans via `ServerLevel.findNearestMapStructure(StructureTags.VILLAGE, pos, 640, false)`, stores/clears component, action-bar feedback
- [x] Compass client: subclass vanilla CompassItemPropertyFunction with a CompassTarget returning the player's exact mirror through the stored village pos (T = 2E - V ⇒ needle points exactly away); reuse vanilla wobble/spin; register via ItemProperties.register under `siasworkshop:angle`; hand-written model JSON (vanilla override table, vanilla compass sprites)
- [x] Foundation block: Block + BlockEntity (flavor int, generated flag, NBT persistence) + Menu (ContainerData sync, clickMenuButton for cycle/create — no custom packets) + Screen; open via useWithoutItem → `player.openMenu(provider, pos)` (IPlayerExtension)
- [x] VillagePlacer: mirror PlaceCommand.placeStructure (structure.generate → valid check → force-load bbox chunks → placeInChunk per chunk); pre-check separation (nearest village structure ≥ 128 blocks); CherryfyProcessor ThreadLocal force flag inside try/finally so only this placement is cherryfied
- [x] Registrations: SiasWorkshopBlockEntities + SiasWorkshopMenus holders, wired in entrypoint; MenuScreens + property registration in client setup; both items in mod creative tab
- [x] Datagen: blockstate/model (cherry planks placeholder), loot (dropSelf); lang entries
- [ ] Build green; user verifies in-game (compass scan + needle, GUI, both flavors, separation refusal, self-gating after success) — confirmed 2026-10-08 after two fix rounds (sections-radius + synthetic-Y; StructureStart registration)

## Progress Log
- [2026-10-08T06:15:25.783Z] Created initiative via mdocs command
- [2026-10-08T06:31:03Z] Implementation complete: compass (item + component + mirrored-target client property + override model), foundation block (BE + menu + screen + VillagePlacer mirroring PlaceCommand), CherryfyProcessor ThreadLocal force, registrations, datagen, lang. Build + runData green. Awaiting in-game verification.
- [2026-10-08T08:35:00Z] User verification round 1 — two bugs found via screenshots (compass reported ≥70-80 even inside a village; block refused at 1481 blocks while compass agreed): `findNearestMapStructure` radius is in SECTIONS (×16) not blocks, and returned positions carry a synthetic Y (rings use y=32, spread potentials y=0) while its internal comparison is 3D. Fixed: radius converted (both tools), horizontal distance everywhere (`VillagePlacer.horizontalDistance`), compass stores target at player Y so the away-needle is purely horizontal, and the scan message now includes a build/no-build verdict vs MIN_SEPARATION. Build green; awaiting re-test.
- [2026-10-08T16:40:14Z] User verification round 2 — compass+verdict confirmed working; block allowed villages arbitrarily close to each other. Root cause: `StructureStart.placeInChunk` never registers the StructureStart (PlaceCommand has the same blind spot), so block-placed villages were invisible to `checkStructurePresence` and the separation check. Fix: after placement, register the start via `structureManager.setStartForStructure(...)` on the start chunk at STRUCTURE_STARTS (mirrors natural worldgen), making block villages visible to the separation check and /locate. Note: villages placed before this fix in existing worlds stay unregistered. Build green.
- [2026-10-08T16:54:00Z] User confirmed all behaviors. Initiative wrapped: wiki worldgen/village-tools-notes written; closing.
- [2026-10-08T16:55:01.799Z] Marked done via mdocs command

## Artifacts
