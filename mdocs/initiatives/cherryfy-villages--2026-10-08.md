---
id: "cherryfy-villages"
title: "Cherry-theme villages (config-gated cosmetic option)"
status: "done"
created: "2026-10-08"
updated: "2026-10-08"
owner: ""
tags: []
related_wiki: ["worldgen/cherryfy-villages", "worldgen/village-generation-notes"]
priority: "medium"
graduated: "2026-10-08"
---

## Objective
Tester feedback (2026-10-08) narrowed the scope: the DEFAULT experience is only phase 1 — villages generating in cherry grove biomes (shipped via biome tag injection, separate completed initiative `cherry-village-worldgen`). Re-skinning villages with cherry materials becomes a CONFIGURABLE OPTION, default OFF, so config-off behavior is byte-identical to vanilla villages. No biome changes, no gameplay impact; the option affects newly generated chunks only.

## Plan
- [x] Create and activate initiative; advance workflow to EXECUTE
- [x] Implement CherryfyProcessor: parametric primary/base/sources/accents mapping (oak family -> cherry; accents per piece from birch/jungle/acacia), registered via SAM-typed factory
- [x] Override plains village template pools (town_centers/streets/houses/terminators) with composed (merged) processor lists; cherry decor tree swapped in
- [x] Verify in-game: cherry materials + per-house accent variety confirmed by user
- [x] Tester decision: default = villages-in-cherry-groves only; cherryfy becomes a config option (default off)
- [x] Add SERVER config `cherryfyVillages` (default false); CherryfyProcessor no-ops when disabled
- [x] Revert decor.json cherry-tree swap to vanilla oak (config-off must be exactly vanilla; decor content can't be gated by a processor)
- [x] Build green
- [x] Verify both modes in-game (user): config off = vanilla villages (still spawning in cherry groves); config on = cherry villages — confirmed 2026-10-08
- [x] Record learnings (wiki `worldgen/cherryfy-villages`); COMPLETE

## Progress Log
- [2026-10-08T02:13:00.960Z] Created initiative via mdocs command
- [2026-10-08T02:45:00.000Z] Tester decision received: default is villages-in-cherry-groves only; cherryfied villages become a config option (default off). Plan revised.
- [2026-10-08T02:55:00.000Z] User confirmed: vanilla oak decor tree is fine even inside cherry groves — no follow-up mechanism for a config-gated cherry tree. Decor stays vanilla.
- [2026-10-08T06:01:11Z] Both modes verified in-game by user (normal worlds + cherry groves). Wiki page worldgen/cherryfy-villages written; initiative closing.
- [2026-10-08T06:01:55.205Z] Marked done via mdocs command

## Artifacts
