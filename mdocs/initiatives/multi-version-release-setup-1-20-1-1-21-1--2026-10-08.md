---
id: "multi-version-release-setup-1-20-1-1-21-1"
title: "Multi-version release setup: 1.20.1 + 1.21.1"
status: "active"
created: "2026-10-08"
updated: "2026-10-08"
owner: ""
tags: ["tooling","release","backport","ci"]
related_wiki: ["setup/multi-version-release-notes"]
---

## Objective

Upgrade the dev setup to release the mod for multiple Minecraft versions, starting with **1.20.1 and 1.21.1** (the two NeoForge-actively-supported versions). Deliverables: versioned branches that each build a working jar, jar names that carry the MC version, and CI that builds both.

## Plan
- [x] Research toolchain + versioning (NeoForge versioning docs per user pointer; MDG 2 legacy plugin covers NeoForge 1.20.1; latest 1.20.1 = 47.1.106; jar naming = MC version + mod version) — filed in wiki `setup/multi-version-release-notes`
- [x] main (1.21.1): include MC version in jar filename (`siasworkshop-1.21.1-1.0.0.jar`) via gradle properties — 61ce56a
- [x] Create `version/1.20.1` branch from main; switch build to MDG legacy plugin (`net.neoforged.moddev.legacyforge`), JDK 17 toolchain, NeoForge `1.20.1-47.1.106`, Parchment 1.20.1 — 60f038a
- [x] Port src to 1.20.1 per the delta checklist in the wiki (data components→NBT is the big one; use/MenuScreens/NetworkHooks/ChunkStatus/structure signatures) — done via coder subagent; build+runData green
- [x] Port/verify resources (pools, tags, processor lists, compass model, lang unchanged; mods.toml converted to FML 47.1 format — modId "forge", mandatory=true; loot regenerated under loot_tables/)
- [x] CI: build both branches (workflow triggers on main + version/*), jar uploaded as artifact — cherry-picked to both branches
- [x] Smoke test: runClient + feature checklist on both versions (compass, foundation, cherryfy, config) — user confirmed 1.20.1 works 2026-10-08 (after adding the 1.20.1-required pack.mcmeta, pack_format 15)
- [ ] Record learnings; COMPLETE

## Architecture decision (correctable)

Branch-per-version: `main` = 1.21.1, `version/1.20.1` = backport branch, cherry-picks flow downward. Single-branch shim setups (Stonecutter) rejected for now — see wiki for rationale. Jar naming: `siasworkshop-<mc>-<modversion>.jar` per NeoForge docs recommendation.

## Progress Log
- [2026-10-08T17:56:50.583Z] Created initiative via mdocs command
- [2026-10-08T17:58:57Z] Researched toolchain + versioning; wiki setup/multi-version-release-notes written; plan recorded.
- [2026-10-08T19:15:00Z] Executed: jar naming on main (61ce56a); version/1.20.1 branch created — MDG legacy plugin via `enable { neoForgeVersion }` (plain version= targets MinecraftForge), JDK 17, 47.1.106, parchment 2023.09.03. Full source port via coder subagent (Forge 47.1 APIs: RegistryObject, ForgeConfigSpec, NetworkHooks, IForgeMenuType, share-NBT compass target, mods.toml in FML 47.1 format). Build + runData green, jar siasworkshop-1.20.1-1.0.0.jar (reobf) — 60f038a. CI workflow builds both branches + uploads jar (ae24579, cherry-picked to main c6a5e50). Findings appended to wiki. Awaiting user smoke test of the 1.20.1 client.
- [2026-10-08T21:05:00Z] User smoke test: dev run showed "failed to load a valid ResourcePackInfo" — fixed by adding src/main/resources/pack.mcmeta (pack_format 15; required ≤1.20.1, absent from the 1.21 template). User confirmed everything works on 1.20.1. Initiative complete.

## Artifacts
