---
id: "endgame-village-tool-recipes"
title: "End-game recipes for village tools"
status: "done"
created: "2026-10-10"
updated: "2026-10-10"
owner: ""
tags: ["recipes","datagen","balance","villages"]
related_wiki: ["worldgen/village-tools-notes"]
priority: "medium"
next_action: "Done"
---

## Objective
Make the wilderness compass and village foundation block obtainable in survival as end-game items via datagen recipes that combine village/villager-themed ingredients (bell, emeralds) with rare items (nether star, shulker box).

## Plan
- [ ] Add ModRecipeProvider datagen provider and register it in ModDataGenerators
- [ ] Add shaped recipes for wilderness_compass and village_foundation
- [ ] Regenerate resources with ./gradlew runData
- [ ] Verify with ./gradlew build and review the diff

## Progress Log
- [2026-10-10T22:29:08.920Z] Created initiative via mdocs command
- PLAN approved by user: Wilderness Compass = Bell + Nether Star + Compass + 2 Emerald Blocks (shaped cross); Village Foundation = 2 Shulker Boxes + Bell + Lodestone + 2 Emerald Blocks + 3 Smooth Stone. Implementation delegated to developer.
- VERIFY passed: runData generated both recipe + advancement JSONs, ./gradlew build green, reviewer found no blocking findings.
- [2026-10-10T22:35:59.313Z] Marked done via mdocs command
- Local smoke test PASSED on 1.21.1 (main): recipe unlock toasts on nether star/shulker box pickup, both items craft per the approved patterns, crafted compass scan + foundation GUI functional.

## Artifacts
