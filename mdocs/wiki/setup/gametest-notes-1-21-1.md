---
id: "gametest-notes-1-21-1"
title: "GameTest Setup Notes (NeoForge 1.21.1)"
category: "setup"
created: "2026-10-14"
updated: "2026-10-14"
related_initiatives: ["agents-v1-spike-entity-observation--2026-10-14"]
tags: ["gametest","testing","neoforge","tooling"]
lifecycle: "stable"
knowledge_type: "how-to"
confidence: "high"
source_initiatives: ["agents-v1-spike-entity-observation--2026-10-14"]
---

Hard-won facts from wiring the first GameTests in this repo (agents v1 spike,
2026-10-14). Verified against the actual `neoforge-21.1.256` jar and a passing
`./gradlew runGameTestServer` run.

- **Discovery is not automatic.** `@GameTest` methods are ignored unless the
  class is annotated `@GameTestHolder("siasworkshop")` (or registered via
  `RegisterGameTestsEvent`). Without it the server dies with
  `IllegalArgumentException: No test functions were given!` — a confusing error
  for "your class wasn't scanned". Namespace scoping comes from
  `neoforge.enabledGameTestNamespaces=siasworkshop` in `build.gradle`.
- **Template naming:** with `@GameTestHolder`, `template = "foo"` resolves to
  structure `<modid>:<classname-lowercase>.foo` — e.g. `template = "empty"` in
  `AgentsGameTest` → `siasworkshop:agentsgametest.empty`. Never put a
  namespaced value like `neoforge:empty` in `template`; the embedded `:` makes
  the composed path an illegal ResourceLocation and the server crashes at
  spawn time (not lookup time).
- **Templates load from the SINGULAR folder** `data/<modid>/structure/<path>.nbt`.
  The plural `structures/` is only the export convention for saved structures on
  disk; the runtime lister is `FileToIdConverter("structure", ".nbt")`.
- **Empty-structure fixture:** we ship a hand-generated minimal air template at
  `src/main/resources/data/siasworkshop/structure/agentsgametest.empty.nbt`
  (133 bytes, gzipped NBT: size [3,3,3], palette [air], empty blocks/entities,
  DataVersion 3955). Regenerate with the Python one-liner recorded in the
  `agents-v1-spike-entity-observation--2026-10-14` initiative progress log if
  ever lost. Larger fixtures should be authored in-game and exported to the
  same folder.
- **Test the player-present paths.** The GameTest server world has **no
  players** — any code that short-circuits on `level.players().isEmpty()` is
  untested by default (this hid a live crash in `AgentObservation.dist()`).
  Use `helper.makeMockServerPlayerInLevel()` — it goes through
  `PlayerList.placeNewPlayer` and lands in `level.players()`. Plain
  `makeMockPlayer()` does NOT (verified via javap on the 21.1.256 jar).
  `makeMockServerPlayerInLevel` is deprecated-for-removal in 21.1.256 —
  revisit on NeoForge bumps. Also: `String.formatted(...)` takes no Locale
  (the Locale would land in varargs); use `String.format(Locale.ROOT, ...)`.
 `Entity` subclasses override
  `public void addAdditionalSaveData(CompoundTag)` /
  `public void readAdditionalSaveData(CompoundTag)` (single-arg; declared
  `public` on `Mob` — overriding with `protected` fails with "attempting to
  assign weaker access privileges"). `BlockEntity` uses the different
  `saveAdditional/loadAdditional(CompoundTag, HolderLookup.Provider)` pair —
  do not mix the two families. `ContainerHelper.saveAllItems/loadAllItems`
  takes a `HolderLookup.Provider`; `entity.registryAccess()` satisfies it.
  `Entity#saveWithoutId` takes only `CompoundTag`.

- **Batch-world races between time-pinning tests.** Tests in one batch share a
  level, so two tests pinning opposite day times (e.g. a shelter detector's
  night) race and flake. Put time-sensitive tests in dedicated
  `batch = "..."` values — batches never overlap
  (agents-v1-decision-pool-goals; `AgentsGoalsGameTest` uses
  `agentsGoalsLight`/`agentsGoalsShelter`).
- **Exposure checks: `canSeeSky`, never `getBrightness(SKY, pos) >= 15`.**
  StarLight stores raw sky light (15 for any sky-open column) and applies the
  night-time skydarken only in `LevelLightEngine.getRawBrightness` — so at
  night the latter returns 4 and a `>= 15` exposure test is silently dead
  code. `Level#canSeeSky(pos)` is the true exposure check at any time of day.
- **The GameTest world PERSISTS between `runGameTestServer` runs** (SavedData,
  entities, and blocks outside template footprints survive in `run/`). This
  made loop tests order-dependent: old board tasks, placed torches, and dead
  agents leaked into the next run. Fix in `build.gradle`: the gameTestServer
  run uses its own `gameDirectory = run-gametest/`, a `doFirst` wipes
  `run-gametest/world` (config-cache-safe: capture `layout.projectDirectory`
  refs, use `File.deleteDir()` — `file()`/`delete` at execution time break
  the configuration cache), and seeds `world/serverconfig/siasworkshop-server.toml`
  (SERVER-type configs live in the world dir, so they must be re-seeded after
  every wipe).
- **`GameTestHelper.onEachTick` is not incremental.** It pre-registers the
  runnable at EVERY remaining tick via `setRunAtTickTime` (a fastutil
  Object2LongOpenHashMap). Calling it from inside a delayed callback mutates
  that map mid-iteration → `NullPointerException: wrapped is null` in
  `GameTestInfo.tickInternal`. Register at test-body time only (guard with
  flags until the test phase arms), or use self-rescheduling `runAfterDelay`.
- **The framework wraps structures in barrier blocks** — frames/platforms
  that satisfy the light detector's "air + rigid floor + no sky" criteria at
  the edges of a radius-8 scan. Any gametest asserting "deficit == 0" must
  either enclose the whole scan volume or shrink the scan (we seed
  `agents.lightScanRadius = 3` for the gametest server).
- **Production tick handlers run concurrently with manually-driven tests.**
  The mod's real `ServerTickEvent` handlers (detectors, decision loop) fire
  during gametests, so tasks can be raised/claimed/completed between a test's
  explicit calls. Assert on resolved state (DONE retains claimantUuid — proof
  of who claimed it), poll with body-time-registered callbacks, pass a
  claim-key filter seam to the loop, and pin `agents.decisionIntervalSeconds`
  high in the seeded TOML so the global decision pass can't fire mid-test.
- **SavedData in 21.1.256:** construct with
  `new SavedData.Factory<>(Supplier, BiFunction<CompoundTag, HolderLookup.Provider, T>, @Nullable DataFixTypes)`;
  per-entry NBT parsing should try/catch-skip malformed entries (one bad tag
  must not kill world load), and schema-version unknown entries are skipped,
  not mangled. Per-world access:
  `level.getDataStorage().computeIfAbsent(FACTORY, DATA_ID)`.

## Referenced By

*Auto-generated by mdocs*

- agents-v1-spike-entity-observation--2026-10-14
