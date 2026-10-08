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
- [ ] main (1.21.1): include MC version in jar filename (`siasworkshop-1.21.1-1.0.0.jar`) via gradle properties
- [ ] Create `version/1.20.1` branch from main; switch build to MDG legacy plugin (`net.neoforged.moddev.legacyforge`), JDK 17 toolchain, NeoForge `1.20.1-47.1.106`, Parchment 1.20.1
- [ ] Port src to 1.20.1 per the delta checklist in the wiki (data components→NBT is the big one; use/MenuScreens/NetworkHooks/ChunkStatus/structure signatures)
- [ ] Port/verify resources (pools, tags, processor lists, compass model, lang; mods.toml dependency ranges for 47.1)
- [ ] CI: build both branches (workflow matrix or per-branch triggers), tagged releases produce both jars
- [ ] Smoke test: runClient + feature checklist on both versions (compass, foundation, cherryfy, config)
- [ ] Record learnings; COMPLETE

## Architecture decision (correctable)

Branch-per-version: `main` = 1.21.1, `version/1.20.1` = backport branch, cherry-picks flow downward. Single-branch shim setups (Stonecutter) rejected for now — see wiki for rationale. Jar naming: `siasworkshop-<mc>-<modversion>.jar` per NeoForge docs recommendation.

## Progress Log
- [2026-10-08T17:56:50.583Z] Created initiative via mdocs command
- [2026-10-08T17:58:57Z] Researched toolchain + versioning; wiki setup/multi-version-release-notes written; plan recorded.

## Artifacts
