# Sia's Workshop

A NeoForge mod about finding your spot and building your village.

- **Wilderness Compass** — scans for the nearest village and points *away* from it, so you
  always know how much separation you have before placing a village of your own.
- **Village Foundation** — a block that generates a full village where you place it.
  Pick cherry blossom or vanilla flavor from its screen.
- **Cherry-themed villages** (optional, server config) — vanilla villages generate
  re-skinned with cherry blossom materials.

## Supported versions

| Minecraft | NeoForge | Branch |
| --- | --- | --- |
| 1.21.1 | 21.1.x | `main` |
| 1.20.1 | 47.1.x | `version/1.20.1` |

## Building

```bash
./gradlew build        # build the jar (build/libs/)
./gradlew runClient    # dev client
./gradlew runData      # regenerate data assets
```

Requires JDK 21 (1.21.1 branch) or JDK 17 (1.20.1 branch); Gradle toolchains
auto-provision via the foojay resolver.

## Layout

Mod sources live under `src/main/java/com/siaws/siawsmod/`. See
[AGENTS.md](AGENTS.md) for the full project guide (structure, conventions, dev loop).

## License

All Rights Reserved — see `gradle.properties` (`mod_license`).
