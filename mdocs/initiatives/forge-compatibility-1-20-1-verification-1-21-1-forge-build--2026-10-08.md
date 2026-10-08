---
id: "forge-compatibility-1-20-1-verification-1-21-1-forge-build"
title: "Forge compatibility: 1.20.1 verification + 1.21.1 Forge build"
status: "active"
created: "2026-10-08"
updated: "2026-10-08"
owner: ""
tags: ["tooling","release","forge","backport"]
related_wiki: []
---

## Objective

Support MinecraftForge (not just NeoForge) on both MC versions. Two very different work items — see the estimate below.

## Effort estimate (2026-10-08)

### A. 1.20.1 Forge support — verification only, ~a few hours
The 1.20.1 branch should **already be Forge-compatible**: it was ported to pure Forge 47.1 APIs (`net.minecraftforge.*`), builds against the Forge-compatible `net.neoforged:forge` artifact, reobfuscates to SRG, and its mods.toml requires `forge [47.1,)`. NeoForge 47.1.x and MinecraftForge 47.1.x are mutually compatible by design.
Work = prove it: run dev client against MinecraftForge 47.1.106 userdev (MDG legacy resolves `net.minecraftforge` when `version =` is used instead of `neoForgeVersion`), load-test the shipped jar on Forge 47.1.106, add a CI check. Worst case: small metadata fixes.

### B. 1.21.1 Forge build (Forge 52.1.x, latest 52.1.16) — a real port, ~2–4 days
Forge and NeoForge diverged at 1.20.2, so 1.21.1 Forge is a different API lineage:
- **Toolchain**: ModDevGradle does not support Forge ≥1.20.2 → new branch needs **ForgeGradle 6** (different build entirely; slower dev loop; Parchment-on-FG for 1.21.1 needs verifying).
- **Code port** (~25 files, agent-assisted like the 1.20.1 backport): packages back to `net.minecraftforge.*`, `RegistryObject` instead of DeferredHolder/DeferredBlock/DeferredItem, Forge config spec, re-solve menu-screen registration (NeoForge's `RegisterMenuScreensEvent` doesn't exist there), drop the NeoForge config-screen extension point, mods.toml syntax for Forge 52. Vanilla APIs we rely on (useWithoutItem, data components, structure pipeline, CompassItemPropertyFunction.CompassTarget) exist on both sides — the port is smaller than the 1.20.1 backport in code, bigger in toolchain risk.
- **Ongoing cost**: every future feature must be translated to a 3rd branch (main 1.21.1-NeoForge cherry-picks won't apply cleanly).

### Recommendation
Do A now (cheap, and 1.20.1 Forge is the biggest Forge player base). Treat B as a separate decision — the 1.21.1 modded ecosystem skews heavily NeoForge, so weigh demand before spending 2–4 days + permanent 3-branch maintenance.

## Plan
- [ ] (Scope pending user decision: A only, or A + B)

## Progress Log
- [2026-10-08T20:07:33.821Z] Created initiative via mdocs command; estimate prepared.

## Artifacts
