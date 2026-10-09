---
id: "multi-version-release-notes"
title: "Multi-Version Release Setup (1.20.1 + 1.21.1)"
category: "setup"
created: "2026-10-08"
updated: "2026-10-09"
related_initiatives: ["multi-version-release-setup-1-20-1-1-21-1","forge-compatibility-1-20-1-verification-1-21-1-forge-build","finalize-initial-release-1-0-0"]
tags: ["tooling","release","ci","versioning","backport"]
lifecycle: "stable"
knowledge_type: "how-to"
confidence: "high"
source_initiatives: ["multi-version-release-setup-1-20-1-1-21-1"]
---

## Sources

- [NeoForge versioning docs](https://docs.neoforged.net/docs/gettingstarted/versioning) (user-recommended reading)
- [ModDevGradle 2 stable announcement](https://neoforged.net/news/moddevgradle2/)
- NeoForge Maven metadata (`net.neoforged:forge` for 1.20.1)

## Version scheme facts

- **Minecraft**: `1.x` era ran 1.0 (2011) → 1.21.11; from 2026 Mojang switched to **calver** `year.release.patch` (26.1 = first drop of 2026). Don't hardcode "MC versions always start with 1".
- **NeoForge**: adapted semver — major = MC minor, minor = MC patch, patch = build number. E.g. 21.1.256 = build 256 for MC 1.21.1; 20.2.59 for 1.20.2.
- **1.20.1 is the exception**: it stayed on the Forge-compatible `47.1.x` numbering (artifact name kept as `net.neoforged:forge`). Latest as of 2026-10-08: **47.1.106**. 1.20.1 NeoForge = drop-in for Forge 47.1 mods.
- **Maven Version Ranges** (used in mods.toml dependency declarations) are not fully semver-compatible (`-pre` tags ignored) — keep mod version strings MVR-compatible.

## Toolchain: one plugin family covers both targets

- **ModDevGradle 2.x** (`net.neoforged.moddev`) is the current plugin for NeoForge 1.20.4+/1.21.x (this repo's template already uses it).
- For **1.20.1** MDG 2 ships a **legacy plugin** (`net.neoforged.moddev.legacyforge`) covering Forge 1.17–1.20.1 and NeoForge 1.20.1. So no ForgeGradle/NeoGradle needed; both branches stay on MDG 2.
- Java: 1.21.1 branch = JDK 21 toolchain; 1.20.1 branch = JDK 17 toolchain (MC 1.20.1 targets Java 17).
- Mappings: Parchment exists for both (1.20.1 and 1.21.1) — keep parchment on the 1.20.1 branch too.

## Mod versioning + jar naming

Per the NeoForge docs' recommendation: include the **MC version and loader in the jar filename** so users can tell files apart — `siasworkshop-1.21.1-1.0.0.jar`, `siasworkshop-1.20.1-1.0.0.jar`. Mod version itself stays plain semver in mods.toml (MVR-safe). Implemented via `archivesName = "${mod_id}-${minecraft_version}"` in build.gradle (base from gradle.properties).

## Architecture decision: branch per version

Ecosystem norm for NeoForge-only mods (Botania, Ender IO, ...): **`main` = newest MC (1.21.1)**, **`version/1.20.1` = backport branch**, features cherry-picked down. Single-branch source-shim setups (Stonecutter etc.) were rejected for now: tooling cost outweighs benefit at our codebase size, and our 1.21.1 code uses APIs that don't exist in 1.20.1 (data components), so shims would be intrusive. CI builds both branches (matrix) and release tags produce both jars.

## Release pipeline (shipped with v1.0.0)

- Repo: `github.com/bbaaxx/siasworkshop` (public). Two workflows: `.github/workflows/build.yml` (push/PR on `main` + `version/*`; uploads the dev jar as an artifact) and `.github/workflows/release.yml` (tag `v*` → checks out `main` and `version/1.20.1` side by side via `actions/checkout` `path:`, builds both with `gradlew -p <dir> build`, publishes ONE GitHub Release with both jars via `softprops/action-gh-release`).
- **GOTCHA — artifact names cannot contain `/`.** `siasworkshop-${{ github.ref_name }}` works on `main` but on `version/1.20.1` the ref_name literally contains a slash, so `actions/upload-artifact` fails AFTER a successful build. Use a static artifact name (e.g. `siasworkshop-dev-jar`).
- A single JDK-21 runner builds both branches: each branch's Gradle toolchain (21 vs 17) auto-provisions via the foojay resolver in settings.gradle.
- Tag-push workflows run from the workflow file as it exists at the tagged commit (fallback: default branch) — keep release.yml on main; tags cut from main carry it.
- Release flow that worked for v1.0.0: push both branches → confirm both branch CI builds green → `git tag -a v1.0.0` on main → push tag → watch Release run → verify release assets contain both jars.

## 1.21.1 → 1.20.1 porting delta checklist

From APIs we verified against 21.1.256 sources this session:

- **Data components → share NBT** (components don't exist pre-1.20.5): `village_target` becomes a long tag (`BlockPos.asLong()` / `BlockPos.of(long)`); property function reads NBT (syncs on stack, same as components).
- `useWithoutItem` → combined `use(BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)` (the split is 1.21.x).
- `MenuScreens.register` is **public** in 1.20.1 (NeoForge's `RegisterMenuScreensEvent` may not exist there — check; vanilla registration is fine).
- `IPlayerExtension.openMenu(provider, pos)` → `NetworkHooks.openScreen(serverPlayer, provider, buf -> buf.writeBlockPos(pos))` in 47.1.
- `CompoundTag`: use `getString`/`getBoolean` (already 1.20.1-compatible — `getStringOr` is 1.21.2+).
- `ChunkStatus` lives at `net.minecraft.world.level.chunk.ChunkStatus` (the `.chunk.status` move is 1.20.5+).
- `StructureManager` package + `StructureStart.placeInChunk`/`Structure.generate` signatures differ — re-verify against 1.20.1 sources once the legacy artifacts resolve (NeoFormRuntime will have them).
- `CompassItemPropertyFunction.CompassTarget` availability in 47.1 — verify; fallback is implementing `ClampedItemPropertyFunction` directly (wobble math is small).
- Resources are largely portable: processor-list/template-pool JSON, biome tag injection, compass override model, lang — all same format in 1.20.1. Watch: `mods.toml` dependency ranges (`forge [47.1,)`, `minecraft [1.20.1,1.21)`), loot dir is `loot_tables/` (plural) on 1.20.1 vs `loot_table/` on 1.21.1.
- Datagen providers (BlockStateProvider/ItemModelProvider/BlockLootSubProvider) — NeoForge 47.1 variants, minor API deltas.
- Registry holders: `RegistryObject` + `net.minecraftforge.registries.DeferredRegister` (Forge package) on 1.20.1 vs `DeferredHolder`/`DeferredItem`/`DeferredBlock` (NeoForge) on 1.21.1.
- Porting flow that worked for the v1.0.0 cleanup: `git worktree add /tmp/<name> version/1.20.1`, apply equivalent edits there (don't raw cherry-pick — the port rewrites the same files), `./gradlew runData build` in the worktree, commit, remove worktree.

## Referenced By

*Auto-generated by mdocs*

- multi-version-release-setup-1-20-1-1-21-1
- forge-compatibility-1-20-1-verification-1-21-1-forge-build
- finalize-initial-release-1-0-0
