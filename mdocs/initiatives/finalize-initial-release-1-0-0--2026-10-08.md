---
id: "finalize-initial-release-1-0-0"
title: "Finalize initial release v1.0.0"
status: "done"
created: "2026-10-08"
updated: "2026-10-09"
owner: ""
tags: ["release","cleanup","textures","ci","tooling"]
related_wiki: ["setup/multi-version-release-notes"]
priority: "medium"
next_action: "Confirm version-branch CI green; then graduate + COMPLETE"
graduated: "2026-10-09"
---

## Objective
Ship v1.0.0 of Sia's Workshop. The mod is tested and accepted; remaining work: (A) strip all MDK template leftovers (example block/item/tab, generated assets, example metadata) and fill every placeholder; (B) repoint the two placeholder-textured content items (village foundation block, wilderness compass) at siasworkshop: texture paths with loading stub PNGs, and write designer-facing art specs (dimensions, resolutions, item descriptions); (C) verify both supported versions (MC 1.21.1 on main, MC 1.20.1 on version/1.20.1) build green and set up the tagged release that publishes both jars.

## Plan
- [ ] A1. Remove example content registrations: EXAMPLE_BLOCK (SiasWorkshopBlocks.java:18), EXAMPLE_BLOCK_ITEM + EXAMPLE_ITEM food (SiasWorkshopItems.java:18,28)
- [ ] A2. Replace EXAMPLE_TAB with real siasworkshop tab (icon: wilderness compass, contains compass + village foundation); drop vanilla Building Blocks injection (SiasWorkshopCreativeTabs.java)
- [ ] A3. Purge example refs from datagen (ModBlockStateProvider.java:17-19, ModItemModelProvider.java:17-19, ModBlockLootTables.java:20); run ./gradlew runData; confirm no example_* generated assets remain
- [ ] A4. Fill neoforge.mods.toml placeholders: real mod description (line 49), drop example URL/logoFile comment lines and example header comments
- [ ] A5. Fix lang: replace Example Mod Tab/Block/Item entries (en_us.json:2-4) with real tab name; confirm compass/foundation keys intact
- [ ] A6. Repo-wide sweep for leftover example/template/placeholder mentions (code comments, README, IDEAS.md, docs)
- [ ] B1. Repoint village_foundation block model at siasworkshop:block/village_foundation (ModBlockStateProvider.java:22 borrows minecraft:block/cherry_planks today); regenerate datagen
- [ ] B2. Repoint wilderness_compass model (currently borrows minecraft:item/compass_16 + 32 vanilla compass override frames) at siasworkshop textures; settle frame strategy for the angle overrides; add stub PNGs so resource loading works
- [ ] B3. Write designer texture spec doc in repo (docs/art/): required dimensions/resolutions and item descriptions for both textures
- [ ] C1. Verify version/1.20.1 branch state: diff vs main, ./gradlew build green on that branch
- [ ] C2. Port/cherry-pick the cleanup + texture-path work to version/1.20.1; rebuild green there
- [ ] C3. Jar naming carries MC version (siasworkshop-1.21.1-1.0.0.jar / siasworkshop-1.20.1-1.0.0.jar) on both branches
- [ ] C4. CI: confirm workflow builds both branches; add release flow where a tag builds both branches and publishes a GitHub Release with both jars
- [ ] C5. Cut the v1.0.0 release artifacts (tag + release; confirm with user before pushing/publishing)
- [ ] V1. Verify: ./gradlew build + runData green on main and version/1.20.1; mdocs validate

## Progress Log
- [2026-10-08T23:32:18.182Z] Created initiative via mdocs command
- Streams A+B done and committed on main (e349a6d): example content/tab/metadata/lang cleaned, siasworkshop texture paths wired with generated stub PNGs, designer spec at docs/art/texture-specs.md, README rewritten, template zip removed. C4 release workflow (.github/workflows/release.yml) added: tag v* builds main + version/1.20.1 and publishes both jars to a GitHub Release. C2: same cleanup ported to version/1.20.1 in a worktree (RegistryObject/Forge API + mods.toml + legacy layout), build running.
- RELEASED: github.com/bbaaxx/siasworkshop created (public); main (e349a6d cleanup + e71f510 release workflow) and version/1.20.1 (7482533 ported cleanup) pushed; tag v1.0.0 published a GitHub Release with siasworkshop-1.21.1-1.0.0.jar + siasworkshop-1.20.1-1.0.0.jar (release workflow built both branches green). Found+fixed: branch CI artifact name used github.ref_name, which contains a slash on version/* branches and fails upload-artifact (760b7bd / 13a9cea, setup-java v5 too). Version-branch CI re-run pending.
- [2026-10-09T15:42:47.019Z] Marked done via mdocs command

## Artifacts
