---
id: "final-village-tool-textures"
title: "Final village tool textures"
status: "done"
created: "2026-10-09"
updated: "2026-10-09"
owner: ""
tags: []
related_wiki: ["setup/final-art-assets"]
priority: "medium"
phase: "done"
handoff_summary: "Delivered all 33 final pixel textures, square logo, source concepts, prompts, reproducible refinement script and missing compass frame models. Datagen, build, JAR-resource checks and mdocs validation pass."
next_action: "Optional in-game visual review; port to 1.20.1 or publish only when requested."
---

## Objective
Replace the Village Foundation and Wilderness Compass stubs with production 16x16 cherry-themed artwork, verify all frames and commit the deliverables.

## Plan
- [x] Inspect asset requirements and existing wiring
- [x] Generate original artwork and prepare exact pixel assets
- [x] Verify dimensions, transparency, rotation and build packaging
- [x] Document final art and commit deliverables

## Progress Log
- [2026-10-09T18:32:03.350Z] Created initiative via mdocs command
- Created sakura compass and foundation art; retained 32 static compass directions and added deterministic regeneration plus static review sheets.
- Datagen and build passed. Verified 33 PNG sizes/alpha, 32 unique needle directions, invariant dial, packaged model references and byte-identical JAR textures. Added the newly requested square CurseForge logo outside mod resources. Static previews reviewed; no interactive client check.
- [2026-10-09T18:53:33.118Z] Marked done via mdocs command

## Artifacts
- `docs/art/texture-delivery.md`: output paths, static previews, reproduction and verification.
- `docs/art/generation-prompts.md`: exact built-in ImageGen prompts.
- `docs/art/logo.png`: 1254×1254 storefront icon, outside mod resources.
- `tools/gen_final_textures.py`: deterministic native-resolution texture refinement.
- 33 production texture PNGs plus 32 generated compass model JSONs.