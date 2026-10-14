package com.siaws.siawsmod.gametest;

import java.util.List;
import java.util.UUID;

import com.siaws.siawsmod.content.agents.AgentObservation;
import com.siaws.siawsmod.content.agents.AgentWorldData;
import com.siaws.siawsmod.content.agents.ObservationBudget;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;
import com.siaws.siawsmod.content.agents.pool.AgentDecisionPool;
import com.siaws.siawsmod.content.agents.pool.PoolEntry;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.ComfortDetectors;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;
import com.siaws.siawsmod.init.SiasWorkshopEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Game tests for the agents v1 goals slice: shared decision pool, task board, comfort
 * detectors, and the observation token budget. Registered via {@link GameTestHolder}.
 *
 * <p>Pool/board lifecycle tests use fresh {@link AgentDecisionPool}/{@link TaskBoard} instances
 * — up to 50 tests share a batch world, and its SavedData is per-level, so hermetic instances
 * keep these deterministic. The detector tests use the level's real board but key every
 * assertion by the unique mock-player UUID, and pin the global day time before each detector
 * pass (the shelter test needs night, the light test's clear step needs day, and they share
 * one level).
 */
@GameTestHolder("siasworkshop")
public final class AgentsGoalsGameTest {
    private AgentsGoalsGameTest() {}

    // --- decision pool -------------------------------------------------------

    @GameTest(template = "empty")
    public static void poolRetrievalRanksTagMatchesAndTrustedFirst(GameTestHelper helper) {
        AgentDecisionPool pool = new AgentDecisionPool();
        long now = 1000;

        PoolEntry untrusted = pool.recordOutcome(List.of("comfort", "light"), "dark hall",
                List.of("light()"), PoolEntry.Outcome.SUCCESS, null, 200, now, 256);
        PoolEntry trusted = pool.recordOutcome(List.of("comfort", "light"), "dark hall again",
                List.of("light()"), PoolEntry.Outcome.SUCCESS, null, 120, now, 256);
        pool.recordSuccess(trusted.id(), now);
        pool.recordSuccess(trusted.id(), now); // successes 3 → trusted at threshold 3
        PoolEntry weakMatch = pool.recordOutcome(List.of("comfort"), "generic",
                List.of("rest()"), PoolEntry.Outcome.SUCCESS, null, 50, now, 256);
        pool.recordOutcome(List.of("build"), "unrelated tower",
                List.of("build(blueprint)"), PoolEntry.Outcome.SUCCESS, null, 400, now, 256);

        List<PoolEntry> result = pool.retrieve(List.of("comfort", "light"), 3, 3, now + 500);

        helper.assertTrue(result.size() == 3, "expected 3 hits, got " + result.size());
        helper.assertTrue(result.get(0).id().equals(trusted.id()),
                "trusted entry should rank first:\n" + result);
        helper.assertTrue(result.get(1).id().equals(untrusted.id()),
                "untrusted full tag match should rank second:\n" + result);
        helper.assertTrue(result.get(2).id().equals(weakMatch.id()),
                "single-tag match should rank third:\n" + result);
        helper.assertTrue(result.get(0).uses() == 1 && result.get(0).lastUsedGameTime() == now + 500,
                "retrieval should stamp uses + lastUsedGameTime:\n" + result.get(0));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void poolEvictsLeastRecentlyUsedBeyondCap(GameTestHelper helper) {
        AgentDecisionPool pool = new AgentDecisionPool();

        PoolEntry first = pool.recordOutcome(List.of("a"), "one", List.of("rest()"),
                PoolEntry.Outcome.SUCCESS, null, 10, 10, 256);
        PoolEntry second = pool.recordOutcome(List.of("b"), "two", List.of("rest()"),
                PoolEntry.Outcome.SUCCESS, null, 10, 20, 256);
        PoolEntry third = pool.recordOutcome(List.of("c"), "three", List.of("rest()"),
                PoolEntry.Outcome.SUCCESS, null, 10, 30, 256);
        PoolEntry fourth = pool.recordOutcome(List.of("d"), "four", List.of("rest()"),
                PoolEntry.Outcome.SUCCESS, null, 10, 40, 3);

        helper.assertTrue(pool.size() == 3, "cap 3 should evict one entry, size " + pool.size());
        helper.assertTrue(pool.entries().stream().noneMatch(e -> e.id().equals(first.id())),
                "never-used oldest entry should be evicted first:\n" + pool.entries());

        pool.retrieve(List.of("b"), 1, 3, 50); // bump second's lastUsed
        PoolEntry fifth = pool.recordOutcome(List.of("e"), "five", List.of("rest()"),
                PoolEntry.Outcome.SUCCESS, null, 10, 60, 3);

        helper.assertTrue(pool.size() == 3, "cap 3 should still hold after second insert");
        helper.assertTrue(pool.entries().stream().noneMatch(e -> e.id().equals(third.id())),
                "never-used third should be evicted before recently-used second:\n" + pool.entries());
        helper.assertTrue(pool.entries().stream().anyMatch(e -> e.id().equals(second.id()))
                && pool.entries().stream().anyMatch(e -> e.id().equals(fourth.id()))
                && pool.entries().stream().anyMatch(e -> e.id().equals(fifth.id())),
                "recently-used and newest entries should survive:\n" + pool.entries());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void poolRoundTripsThroughNbt(GameTestHelper helper) {
        AgentDecisionPool pool = new AgentDecisionPool();
        long now = 1000;
        PoolEntry success = pool.recordOutcome(List.of("comfort", "light"), "dark hall",
                List.of("light()"), PoolEntry.Outcome.SUCCESS, null, 200, now, 256);
        PoolEntry failure = pool.recordOutcome(List.of("comfort", "shelter"), "open field",
                List.of("place(block)"), PoolEntry.Outcome.FAILED, "no_blocks", 80, now, 256);
        pool.retrieve(List.of("comfort", "light"), 1, 3, now + 100);

        CompoundTag tag = pool.save(new CompoundTag(), helper.getLevel().registryAccess());
        AgentDecisionPool reloaded = AgentDecisionPool.read(tag, helper.getLevel().registryAccess());

        helper.assertTrue(reloaded.size() == 2, "round trip lost entries: " + reloaded.size());
        PoolEntry reloadedSuccess = reloaded.entries().stream()
                .filter(e -> e.id().equals(success.id())).findFirst().orElseThrow();
        helper.assertTrue(reloadedSuccess.tags().equals(List.of("comfort", "light"))
                        && reloadedSuccess.outcome() == PoolEntry.Outcome.SUCCESS
                        && reloadedSuccess.plan().equals(List.of("light()"))
                        && reloadedSuccess.situationSummary().equals("dark hall")
                        && reloadedSuccess.durationTicks() == 200,
                "success entry fields not preserved:\n" + reloadedSuccess);
        helper.assertTrue(reloadedSuccess.uses() == 1 && reloadedSuccess.lastUsedGameTime() == now + 100,
                "uses/lastUsed not persisted:\n" + reloadedSuccess);
        PoolEntry reloadedFailure = reloaded.entries().stream()
                .filter(e -> e.id().equals(failure.id())).findFirst().orElseThrow();
        helper.assertTrue(reloadedFailure.outcome() == PoolEntry.Outcome.FAILED
                        && "no_blocks".equals(reloadedFailure.failureReason())
                        && reloadedFailure.uses() == 0 && reloadedFailure.successes() == 0,
                "failure entry fields not preserved:\n" + reloadedFailure);
        helper.succeed();
    }

    // --- task board ----------------------------------------------------------

    @GameTest(template = "empty")
    public static void boardRaiseClaimSweepLifecycle(GameTestHelper helper) {
        TaskBoard board = new TaskBoard();
        long now = 1000;
        String key = "comfort:light:" + UUID.randomUUID();

        BoardTask first = board.raiseIfAbsent(key, BoardTask.Type.COMFORT_LIGHT, "3 dark spots", now);
        BoardTask again = board.raiseIfAbsent(key, BoardTask.Type.COMFORT_LIGHT, "3 dark spots", now + 10);
        helper.assertTrue(first.id().equals(again.id()), "raiseIfAbsent should return the existing active task");
        helper.assertTrue(board.tasks().size() == 1, "singleton deficit raised twice:\n" + board.tasks());
        BoardTask refreshed = board.raiseIfAbsent(key, BoardTask.Type.COMFORT_LIGHT, "7 dark spots", now + 15);
        helper.assertTrue("7 dark spots".equals(refreshed.detail()),
                "persisting deficit should refresh the OPEN task's detail: " + refreshed.detail());

        UUID claimantA = UUID.randomUUID();
        UUID claimantB = UUID.randomUUID();
        helper.assertTrue(board.claim(first.id(), claimantA, 100, now + 20), "OPEN task should be claimable");
        BoardTask unchangedDetail = board.raiseIfAbsent(key, BoardTask.Type.COMFORT_LIGHT, "99 dark spots", now + 25);
        helper.assertTrue("7 dark spots".equals(unchangedDetail.detail()),
                "CLAIMED task detail must not be rewritten by the detector: " + unchangedDetail.detail());
        helper.assertFalse(board.claim(first.id(), claimantB, 100, now + 30),
                "second claim must fail while the lease holds");
        helper.assertTrue(board.sweepLeases(now + 50) == 0, "lease still valid, nothing to sweep");

        helper.assertTrue(board.sweepLeases(now + 200) == 1, "expired lease should be swept");
        BoardTask swept = board.tasks().get(0);
        helper.assertTrue(swept.status() == BoardTask.Status.FAILED, "swept task should be FAILED: " + swept);
        helper.assertTrue("lease_expired".equals(swept.failureReason()), "swept reason: " + swept.failureReason());
        helper.assertTrue(board.activeTask(key) == null, "FAILED task must not stay active");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void boardLeaseBoundaryStealAndClaimantChecks(GameTestHelper helper) {
        TaskBoard board = new TaskBoard();
        long claimTime = 1000;

        // Boundary: leaseTicks 100 claimed at t → spent at exactly t+100.
        BoardTask task = board.raiseIfAbsent("comfort:light:" + UUID.randomUUID(),
                BoardTask.Type.COMFORT_LIGHT, "dark", claimTime);
        UUID claimantA = UUID.randomUUID();
        UUID claimantB = UUID.randomUUID();
        helper.assertTrue(board.claim(task.id(), claimantA, 100, claimTime), "initial claim");
        helper.assertFalse(board.claim(task.id(), claimantB, 100, claimTime + 99),
                "steal one tick before the lease is spent must fail");
        helper.assertTrue(board.claim(task.id(), claimantB, 100, claimTime + 100),
                "steal at exactly leaseUntil must succeed (lease is spent)");

        // Claimant verification: only the claimant resolves a CLAIMED task.
        helper.assertFalse(board.complete(task.id(), claimantA, claimTime + 110, null, 256),
                "previous claimant must not resolve a stolen task");
        helper.assertFalse(board.fail(task.id(), claimantA, "stalled", claimTime + 110, null, 256),
                "previous claimant must not fail a stolen task");
        helper.assertTrue(board.complete(task.id(), claimantB, claimTime + 110, null, 256),
                "current claimant should resolve");
        helper.assertTrue(board.tasks().get(0).status() == BoardTask.Status.DONE,
                "task should be DONE: " + board.tasks().get(0));

        // Sweep boundary: lease spent at exactly now == leaseUntil.
        BoardTask second = board.raiseIfAbsent("comfort:shelter:" + UUID.randomUUID(),
                BoardTask.Type.COMFORT_SHELTER, "no bed", claimTime);
        helper.assertTrue(board.claim(second.id(), claimantA, 100, claimTime), "second claim");
        helper.assertTrue(board.sweepLeases(claimTime + 99) == 0, "one tick early: nothing swept");
        helper.assertTrue(board.sweepLeases(claimTime + 100) == 1, "at exactly leaseUntil: swept");
        helper.assertTrue(board.tasks().get(1).status() == BoardTask.Status.FAILED,
                "second task should be FAILED: " + board.tasks().get(1));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void boardRoundTripsThroughNbt(GameTestHelper helper) {
        TaskBoard board = new TaskBoard();
        AgentDecisionPool pool = new AgentDecisionPool();
        long now = 1000;
        UUID claimant = UUID.randomUUID();

        BoardTask done = board.raiseIfAbsent("comfort:light:" + UUID.randomUUID(),
                BoardTask.Type.COMFORT_LIGHT, "3 dark spots", now);
        helper.assertTrue(board.claim(done.id(), claimant, 100, now + 10), "claim done task");
        helper.assertTrue(board.complete(done.id(), claimant, now + 50, pool, 256), "resolve done task");

        BoardTask failed = board.raiseIfAbsent("comfort:shelter:" + UUID.randomUUID(),
                BoardTask.Type.COMFORT_SHELTER, "no bed", now);
        helper.assertTrue(board.claim(failed.id(), claimant, 100, now + 10), "claim failed task");
        helper.assertTrue(board.fail(failed.id(), claimant, "no_blocks", now + 30, pool, 256), "resolve failed task");

        BoardTask open = board.raiseIfAbsent("comfort:light:" + UUID.randomUUID(),
                BoardTask.Type.COMFORT_LIGHT, "open one", now);

        CompoundTag tag = board.save(new CompoundTag(), helper.getLevel().registryAccess());
        TaskBoard reloaded = TaskBoard.read(tag, helper.getLevel().registryAccess());

        helper.assertTrue(reloaded.tasks().size() == 3, "round trip lost tasks: " + reloaded.tasks().size());
        BoardTask reloadedDone = reloaded.tasks().stream().filter(t -> t.id().equals(done.id()))
                .findFirst().orElseThrow();
        helper.assertTrue(reloadedDone.status() == BoardTask.Status.DONE
                        && claimant.equals(reloadedDone.claimantUuid())
                        && reloadedDone.resolvedAtGameTime() == now + 50
                        && reloadedDone.type() == BoardTask.Type.COMFORT_LIGHT,
                "DONE task fields not preserved:\n" + reloadedDone);
        BoardTask reloadedFailed = reloaded.tasks().stream().filter(t -> t.id().equals(failed.id()))
                .findFirst().orElseThrow();
        helper.assertTrue(reloadedFailed.status() == BoardTask.Status.FAILED
                        && "no_blocks".equals(reloadedFailed.failureReason())
                        && claimant.equals(reloadedFailed.claimantUuid()),
                "FAILED task fields not preserved:\n" + reloadedFailed);
        BoardTask reloadedOpen = reloaded.tasks().stream().filter(t -> t.id().equals(open.id()))
                .findFirst().orElseThrow();
        helper.assertTrue(reloadedOpen.status() == BoardTask.Status.OPEN && reloadedOpen.claimantUuid() == null,
                "OPEN task fields not preserved:\n" + reloadedOpen);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void boardReapsResolvedHistory(GameTestHelper helper) {
        TaskBoard board = new TaskBoard();
        long now = 1000;
        UUID claimant = UUID.randomUUID();

        for (int i = 0; i < TaskBoard.MAX_RESOLVED_TASKS + 2; i++) {
            BoardTask task = board.raiseIfAbsent("comfort:light:reap-" + i, BoardTask.Type.COMFORT_LIGHT, "dark", now + i);
            helper.assertTrue(board.claim(task.id(), claimant, 1000, now + i), "claim reap task " + i);
            helper.assertTrue(board.complete(task.id(), claimant, now + i + 10, null, 256), "resolve reap task " + i);
        }
        helper.assertTrue(board.tasks().size() == TaskBoard.MAX_RESOLVED_TASKS + 2, "setup should overflow the cap");

        helper.assertTrue(board.sweepLeases(now + 5000) == 0, "nothing claimed, nothing swept");
        helper.assertTrue(board.tasks().size() == TaskBoard.MAX_RESOLVED_TASKS,
                "resolved history should be reaped to the cap, size " + board.tasks().size());
        helper.assertTrue(board.tasks().stream().anyMatch(t -> t.key().equals("comfort:light:reap-129")),
                "newest resolved task should survive the reap");
        helper.assertTrue(board.tasks().stream().noneMatch(t -> t.key().equals("comfort:light:reap-0")),
                "oldest resolved task should have been reaped");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void boardClearAndAutoCompleteResolveDeficit(GameTestHelper helper) {
        TaskBoard board = new TaskBoard();
        AgentDecisionPool pool = new AgentDecisionPool();
        long now = 1000;

        String openKey = "comfort:light:" + UUID.randomUUID();
        board.raiseIfAbsent(openKey, BoardTask.Type.COMFORT_LIGHT, "2 dark spots", now);
        helper.assertTrue(board.clearIfOpen(openKey), "OPEN task should clear when the deficit is gone");
        helper.assertTrue(board.tasks().isEmpty(), "cleared task should be removed entirely");
        helper.assertTrue(pool.size() == 0, "clearIfOpen must not write a pool entry");

        String claimedKey = "comfort:shelter:" + UUID.randomUUID();
        BoardTask raised = board.raiseIfAbsent(claimedKey, BoardTask.Type.COMFORT_SHELTER, "no bed", now);
        helper.assertTrue(board.claim(raised.id(), UUID.randomUUID(), 100, now + 10), "claim for auto-complete");
        int poolBefore = pool.size();
        BoardTask completed = board.autoCompleteClaimed(claimedKey, now + 20, pool, 256);
        helper.assertTrue(completed != null && completed.status() == BoardTask.Status.DONE,
                "autoCompleteClaimed should resolve DONE: " + completed);
        helper.assertTrue(pool.size() == poolBefore + 1, "auto-complete should record a pool success");
        PoolEntry recorded = pool.entries().get(pool.size() - 1);
        helper.assertTrue(recorded.outcome() == PoolEntry.Outcome.SUCCESS
                        && recorded.tags().contains("comfort") && recorded.tags().contains("shelter"),
                "pool entry should be a comfort/shelter success: " + recorded);
        helper.succeed();
    }

    // --- comfort detectors ---------------------------------------------------

    // Detector tests run in their own batches: batches never overlap, and both detectors
    // depend on the global day time, which these two tests pin to opposite values.

    @GameTest(template = "empty", batch = "agentsGoalsLight", timeoutTicks = 300)
    public static void lightDeficitRaisesAndClears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(6000); // noon: open-air positions are never light deficits
        ServerPlayer player = placeMockPlayer(helper, new BlockPos(0, 1, 0));
        String key = "comfort:light:" + player.getUUID();
        // Enclosed stone box; the y=0 shell layer is the floor the mock player lands on after
        // falling, and the interior stays dark (block light 0) regardless of day time.
        fillBox(helper, new BlockPos(-2, 0, -2), new BlockPos(2, 3, 2), Blocks.STONE, true);

        helper.runAfterDelay(40, () -> {
            try {
                ComfortDetectors.runOnce(level);
                BoardTask raised = AgentWorldData.board(level).activeTask(key);
                helper.assertTrue(raised != null && raised.type() == BoardTask.Type.COMFORT_LIGHT,
                        "dark enclosed interior should raise a light deficit:\n"
                                + AgentWorldData.board(level).tasks());
                helper.assertTrue(raised != null && raised.status() == BoardTask.Status.OPEN,
                        "fresh deficit should be OPEN: " + raised);

                // One torch inside, one on the lid: every air position with a rigid floor in
                // scan range ends up above threshold 4. Block light is independent of the
                // global day time, which other tests move freely.
                helper.setBlock(new BlockPos(0, 1, 0), Blocks.TORCH);
                helper.setBlock(new BlockPos(0, 4, 0), Blocks.TORCH);
                helper.runAfterDelay(20, () -> {
                    try {
                        ComfortDetectors.runOnce(level);
                        helper.assertTrue(AgentWorldData.board(level).activeTask(key) == null,
                                "lit interior should clear the light deficit:\n"
                                        + AgentWorldData.board(level).tasks());
                        level.getServer().getPlayerList().remove(player);
                        helper.succeed();
                    } catch (Throwable t) {
                        level.getServer().getPlayerList().remove(player);
                        throw t;
                    }
                });
            } catch (Throwable t) {
                level.getServer().getPlayerList().remove(player);
                throw t;
            }
        });
    }

    @GameTest(template = "empty", batch = "agentsGoalsShelter", timeoutTicks = 300)
    public static void shelterDeficitRaisesAndClearsAtNight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(13000); // no other test in the batch moves the clock
        ServerPlayer player = placeMockPlayer(helper, new BlockPos(0, 1, 0));
        String key = "comfort:shelter:" + player.getUUID();
        // Floor to land on; without it the player falls to the flat world floor ~120 blocks down.
        fillBox(helper, new BlockPos(-2, 0, -2), new BlockPos(2, 0, 2), Blocks.STONE, false);

        helper.runAfterDelay(40, () -> {
            try {
                ComfortDetectors.runOnce(level);
                BoardTask raised = AgentWorldData.board(level).activeTask(key);
                helper.assertTrue(raised != null && raised.type() == BoardTask.Type.COMFORT_SHELTER,
                        "night with no bed should raise a shelter deficit:\n"
                                + AgentWorldData.board(level).tasks());

                // Bed within radius + one opaque layer overhead: not exposed, bed reachable.
                helper.setBlock(new BlockPos(2, 1, 2), Blocks.RED_BED);
                fillBox(helper, new BlockPos(-1, 3, -1), new BlockPos(1, 3, 1), Blocks.STONE, false);
                helper.runAfterDelay(20, () -> {
                    try {
                        ComfortDetectors.runOnce(level);
                        helper.assertTrue(AgentWorldData.board(level).activeTask(key) == null,
                                "bed + cover should clear the shelter deficit:\n"
                                        + AgentWorldData.board(level).tasks());
                        level.getServer().getPlayerList().remove(player);
                        helper.succeed();
                    } catch (Throwable t) {
                        level.getServer().getPlayerList().remove(player);
                        throw t;
                    }
                });
            } catch (Throwable t) {
                level.getServer().getPlayerList().remove(player);
                throw t;
            }
        });
    }

    /**
     * Exposure check: at night a player with a bed nearby but no cover is still exposed —
     * raw sky light reads 15 for any column open to the sky regardless of the time of day.
     */
    @GameTest(template = "empty", batch = "agentsGoalsShelter", timeoutTicks = 300)
    public static void shelterDeficitWhenExposedDespiteBed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(13000);
        ServerPlayer player = placeMockPlayer(helper, new BlockPos(0, 1, 0));
        String key = "comfort:shelter:" + player.getUUID();
        fillBox(helper, new BlockPos(-2, 0, -2), new BlockPos(2, 0, 2), Blocks.STONE, false);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.RED_BED); // bed in range, but no cover yet

        helper.runAfterDelay(40, () -> {
            try {
                ComfortDetectors.runOnce(level);
                BoardTask raised = AgentWorldData.board(level).activeTask(key);
                helper.assertTrue(raised != null && raised.type() == BoardTask.Type.COMFORT_SHELTER,
                        "night + bed + no cover should still raise (exposed):\n"
                                + AgentWorldData.board(level).tasks());

                fillBox(helper, new BlockPos(-1, 3, -1), new BlockPos(1, 3, 1), Blocks.STONE, false);
                helper.runAfterDelay(20, () -> {
                    try {
                        ComfortDetectors.runOnce(level);
                        helper.assertTrue(AgentWorldData.board(level).activeTask(key) == null,
                                "cover over the bedded player should clear the deficit:\n"
                                        + AgentWorldData.board(level).tasks());
                        level.getServer().getPlayerList().remove(player);
                        helper.succeed();
                    } catch (Throwable t) {
                        level.getServer().getPlayerList().remove(player);
                        throw t;
                    }
                });
            } catch (Throwable t) {
                level.getServer().getPlayerList().remove(player);
                throw t;
            }
        });
    }

    // --- observation budget ---------------------------------------------------

    @GameTest(template = "empty")
    public static void observationFitsTokenBudget(GameTestHelper helper) {
        WorkshopAgentEntity agent = spawnAgent(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            BlockPos near = helper.absolutePos(new BlockPos(2, 1, 0));
            player.moveTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5, 0.0F, 0.0F);

            String snapshot = AgentObservation.build(agent, 900);
            int tokens = ObservationBudget.estimateTokens(snapshot);
            helper.assertTrue(tokens <= 900, "snapshot over budget: " + tokens + " tokens:\n" + snapshot);
            helper.assertTrue(snapshot.contains("[SELF]") && snapshot.contains("[AVAILABLE ACTIONS]"),
                    "SELF and AVAILABLE ACTIONS must always survive:\n" + snapshot);
        } finally {
            level.getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void observationCompressesBottomUpUnderLowBudget(GameTestHelper helper) {
        WorkshopAgentEntity agent = spawnAgent(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            BlockPos near = helper.absolutePos(new BlockPos(2, 1, 0));
            player.moveTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5, 0.0F, 0.0F);

            // 100 tokens (400 chars) sits safely below even the fully-compressed snapshot, so
            // every compression level is guaranteed to apply; 120 was within ~2 tokens of the
            // PLAYERS-compression boundary for this scene and could flake either way.
            String snapshot = AgentObservation.build(agent, 100);
            helper.assertTrue(snapshot.contains("[SELF]") && snapshot.contains("[AVAILABLE ACTIONS]"),
                    "SELF and AVAILABLE ACTIONS must always survive:\n" + snapshot);

            // Compression runs bottom-up in salience order; if PLAYERS is compressed, every
            // earlier section is too. ENVIRONMENT shrinks to the one-liner (no biome/weather),
            // PLAYERS drops per-player detail (no hp=/food=/held=).
            String environmentLine = sectionLine(snapshot, "[ENVIRONMENT]");
            String playersLine = sectionLine(snapshot, "[PLAYERS]");
            helper.assertTrue(environmentLine != null && !environmentLine.contains(" biome="),
                    "ENVIRONMENT should be compressed to the minimal one-liner:\n" + environmentLine);
            helper.assertTrue(playersLine != null && !playersLine.contains(" hp="),
                    "PLAYERS should be compressed to name@distance only:\n" + playersLine);
        } finally {
            level.getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    private static String sectionLine(String snapshot, String header) {
        int start = snapshot.indexOf(header);
        if (start < 0) {
            return null;
        }
        int end = snapshot.indexOf('\n', start);
        return end < 0 ? snapshot.substring(start) : snapshot.substring(start, end);
    }

    // --- helpers ----------------------------------------------------------------

    private static ServerPlayer placeMockPlayer(GameTestHelper helper, BlockPos relative) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos absolute = helper.absolutePos(relative);
        player.moveTo(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5, 0.0F, 0.0F);
        return player;
    }

    private static void fillBox(GameTestHelper helper, BlockPos minRelative, BlockPos maxRelative,
            Block block, boolean hollow) {
        for (int x = minRelative.getX(); x <= maxRelative.getX(); x++) {
            for (int y = minRelative.getY(); y <= maxRelative.getY(); y++) {
                for (int z = minRelative.getZ(); z <= maxRelative.getZ(); z++) {
                    if (hollow && x > minRelative.getX() && x < maxRelative.getX()
                            && y > minRelative.getY() && y < maxRelative.getY()
                            && z > minRelative.getZ() && z < maxRelative.getZ()) {
                        continue;
                    }
                    helper.setBlock(new BlockPos(x, y, z), block);
                }
            }
        }
    }

    private static WorkshopAgentEntity spawnAgent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorkshopAgentEntity agent = new WorkshopAgentEntity(SiasWorkshopEntities.WORKSHOP_AGENT.get(), level);
        BlockPos spawn = helper.absolutePos(new BlockPos(0, 1, 0));
        agent.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(agent);
        helper.assertFalse(agent.isRemoved(), "agent failed to spawn");
        return agent;
    }
}
