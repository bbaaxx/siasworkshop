---
id: "gametest-notes-1-21-1"
title: "GameTest Setup Notes (NeoForge 1.21.1)"
category: "setup"
created: "2026-10-14"
updated: "2026-10-14"
related_initiatives: ["post-polish-batch-kev-coverage-recalibration-comfort-shelter-executor-mdocs-graph-hygiene", "agents-v1-spike-entity-observation--2026-10-14", "build-executor-self-entombment-station-material-gaps-live-test-2026-10-22", "agents-action-surface-expansion-hands-materials-economy-roadmap"]
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
- **The production decide fires CROSS-BATCH (executor-wart polish, 2026-10-20).**
  Batches share the level and `ticksUntilRun` is only reset on server STOP, so
  the production loop's unfiltered decide lands every
  `decisionIntervalSeconds` (600 ticks at the seeded 30 s) in WHATEVER batch is
  running then. Two corollaries, both burned into red full-suite runs: (1) any
  idle agent left alive by an earlier batch gets claimed onto a later batch's
  open task (stray agents must be `discard()`ed — see AgentsMcpGameTest — and a
  released OPEN task must be `clearIfOpen`'d before the test ends); (2) any test
  that claims "the open task" via the global `openTasks` list (e.g.
  `handleMcpResult` fallback tests) must first filter to its own key. Also:
  `runDecisions(filter)` applies the filter to EVERY agent in the level, so a
  decide pass inside a test body can hand a just-released OPEN task to a sibling
  test's idle agent — executor tests should claim directly via `board.claim`
  and drive only `runExecutionPass`.
- **The shared batch world has no spatial isolation (executor-wart polish,
  2026-10-20).** The `*.empty.nbt` fixture is a 3×3×3 AIR cube (133 bytes);
  adding tests to a batch repacks the structure grid and can park foreign
  structures within a few blocks, where a neighbor's `fillBox`/`setBlock`
  (relative coords, another origin) or another agent's placements can overwrite
  cells your test asserts on. Rule: assert world-state only atomically in the
  test body (setup → single execution pass → assert → cleanup in one tick), or
  enclose/author the geometry yourself; never assert "cell stays X" across
  ticks unless you control the whole volume.
- **The empty template is wrapped in a barrier shell (post-polish batch,
  2026-10-21).** The 3×3×3 air cube sits inside a vanilla GameTest barrier
  frame covering roughly rel −1..3 in each axis (the cap at rel y=3 is solid
  barrier). Blocks placed "above the player" at rel y≈2–3 hit the shell, the
  shell walls off pathing into the core, and the natural superflat surface
  outside the shell sits one block LOWER (rel y=0 feet, ground at −1) than the
  template's barrier platform. Camp test geometry at rel ≥5 (e.g. (10,1,10))
  and remember entities may rest one block above the surface they stand on —
  "place on the floor beside X" needs a dy∈{0,−1} scan, not a fixed cell.
- **`ProgressWatchdog` measures double positions, not block cells
  (executor-wart polish, 2026-10-20).** Block-position distances are quantized
  to 1-block² steps: a walking agent moves ~0.05 blocks/tick and only changes
  block cell every few ticks, so an integer-cell progress metric trips stuck
  mid-walk (burned `lightTaskEndToEnd`). Measure `position().distanceToSqr`
  against the target center with a ~1e-4 epsilon; slow real walking then
  resets every tick while circling/standing still accumulates.
- **SavedData in 21.1.256:** construct with
  `new SavedData.Factory<>(Supplier, BiFunction<CompoundTag, HolderLookup.Provider, T>, @Nullable DataFixTypes)`;
  per-entry NBT parsing should try/catch-skip malformed entries (one bad tag
  must not kill world load), and schema-version unknown entries are skipped,
  not mangled. Per-world access:
  `level.getDataStorage().computeIfAbsent(FACTORY, DATA_ID)`.
- **A blocked (ledger-parked) task is invisible to the cross-batch production
  decide** (build-executor fixes, 2026-10-22): `openTasks()` filters
  knowledge-blocked ids, so single-pass ledger assertions (park → assert
  entry + unclaimability) cannot be poached mid-assert by the unfiltered
  production `runDecisions` — the blocked filter does double duty as a
  concurrency guard.
- **Never assert on `leaseUntilGameTime` within one game tick** (build-executor
  fixes, 2026-10-22): the lease is always `now + leaseTicks`, and several
  manual `runExecutionPass` calls inside one test body all read the SAME
  `level.getGameTime()` — a renewal is indistinguishable from no renewal
  (`610 > 610` is false even though renew ran). Cost two debug rounds and a
  temporary LOGGER instrumentation to "prove" an executor starvation that did
  not exist. Assert state the execution actually changes (blocks placed,
  inventory counts), or drive the loop across real ticks instead.
- **Entities cannot path in the batch world** (build-executor fixes,
  2026-10-22): test agents at the far-flung random positions of the GameTest
  world never get navigation paths (`moveTo` yields hasPath=false, entities
  often don't even tick/fall) — vanilla locomotion there is unreliable, and
  NO pre-existing test ever exercised real walking (they all spawned the agent
  within the old 2-block arrival radius). Drive the DECISIONS, not the
  locomotion: assert the boxed-in/staging choices, then simulate the walk with
  `agent.moveTo(...)` and exercise placement (which is remote by design).
  Watch out: a "passing" walk-dependent test may be passing on worldgen luck
  (natural stone at the anchor cell counts as placed).
- **A test that fails BEFORE its `agent.discard()` leaks the agent into every
  later batch** (break-primitive slice, 2026-10-25): the unfiltered production
  decide claims open tasks for the stray — symptoms appear in UNRELATED tests
  ("our agent should hold the light claim", shelter roof cells missing, plain
  timeouts) one or two batches LATER, and the failing set shifts run to run as
  tick phases move. Wrap every spawned-agent test body in
  `try { ... } finally { agent.discard(); }` — cleanup must run on the failure
  path too.
- **Build staging tests settle one block lower than spawned** (2026-10-22):
  outside the barrier shell the natural surface is a block lower than the
  template platform, so an agent spawned at rel y=1 rests with feet at rel
  y=0 — anchor the "agent stands inside the footprint" scenario at the
  settled feet cell (rel y=0), not the spawn cell.

## Referenced By

*Auto-generated by mdocs*

- agents-v1-spike-entity-observation--2026-10-14
