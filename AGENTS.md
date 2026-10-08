# Sia's Workshop — Agent Guide

NeoForge mod for Minecraft 1.21.1 (NeoForge 21.1.x, Java 21, Gradle wrapper 9.2.1, ModDevGradle 2.x, Parchment mappings). Mod id: `siasworkshop`.

## Dev loop

| Task | Command |
| --- | --- |
| Build | `./gradlew build` |
| Run client | `./gradlew runClient` |
| Run server (no GUI) | `./gradlew runServer` |
| Run gametests | `./gradlew runGameTestServer` |
| Datagen (regenerate JSON assets) | `./gradlew runData` |
| Refresh dependencies | `./gradlew --refresh-dependencies` |
| Reset build outputs | `./gradlew clean` |

If `./gradlew` fails with `Permission denied`, run `chmod +x gradlew` — extracting the template zip drops the execute bit (Eclipse Buildship is unaffected, which masks it until the first CLI run).

## Structure

```
src/main/java/com/siaws/siawsmod/
├── SiasWorkshop.java        Mod entrypoint — wiring only (registers init holders, config, listeners)
├── SiasWorkshopClient.java  Client-only entrypoint (dist = CLIENT)
├── config/Config.java       ModConfigSpec definitions
├── init/                    One holder class per registry (SiasWorkshopBlocks, SiasWorkshopItems,
│                            SiasWorkshopCreativeTabs, SiasWorkshopStructureProcessors) — add
│                            BlockEntities/Menus/Recipes/etc. here
├── content/                 One package per feature, co-locating its Block/Item/BE/Menu/Screen classes
├── data/                    Datagen providers (blockstates, models, loot); output → src/generated/resources/
└── worldgen/                Structure processors + wood-family mapping (village cherryfying)
src/main/resources/          Hand-written assets; neoforge.mods.toml lives in src/main/templates/ (property-expanded)
src/generated/resources/     Datagen output — do not hand-edit; regenerate with runData
run/                         Dev runtime dir (gitignored)
```

## Conventions

- All registrations live in `init/` holders via `DeferredRegister`; the entrypoint only wires them to the mod event bus.
- Vanilla datapack overrides live in `src/main/resources/data/minecraft/` (tags, template pools) and must stay byte-identical to vanilla except the intended change; worldgen behavior changes are gated via `config/Config.java`.
- GUI pattern (see `content/village/`): `MenuType` via `IMenuTypeExtension.create` + BlockEntity `MenuProvider`, open with `player.openMenu(be, pos)`; screen buttons go through `handleInventoryButtonClick`/`clickMenuButton` (no custom packets); state sync via `ContainerData`; screens registered in NeoForge's `RegisterMenuScreensEvent` (`MenuScreens.register` is private in 1.21.1).
- Content assets (blockstates, models, loot tables, tags, recipes) come from datagen (`runData`) wherever possible; only textures/sounds are hand-written under `src/main/resources/assets/siasworkshop/`.
- Language keys are prefixed `siasworkshop.`; see `assets/siasworkshop/lang/en_us.json`.
- Placeholder art currently borrows vanilla textures (iron block, apple) — replace when real textures arrive.
- Mod metadata (id, version, license, name) is driven by `gradle.properties` and expanded into `neoforge.mods.toml` at build time — edit properties, not the TOML.

## References

Borrow levels, license constraints, and repo/branch pointers for exemplary mods and libraries (Ender IO, Botania, Thermal, MCA Reborn, GeckoLib, Bookshelf, Cloth Config, ...) live in the mdocs wiki: start at `reference/ecosystem-map`.

```bash
./node_modules/.bin/mdocs search <query>   # search initiatives + wiki
./node_modules/.bin/mdocs status           # workflow state
```

## Licensing stance

The mod is All Rights Reserved (`mod_license` in `gradle.properties`). Linking MIT/LGPL libraries as dependencies is fine; never copy GPL/custom-licensed code into this repo — the wiki's `reference/` pages mark what is safe to copy vs. imitate.

## Project memory

This repo uses mdocs (harness-mdocs): tracked work as initiatives, durable knowledge as wiki pages under `mdocs/`. Start an initiative for non-trivial work; run `./node_modules/.bin/mdocs validate` before claiming completion.
