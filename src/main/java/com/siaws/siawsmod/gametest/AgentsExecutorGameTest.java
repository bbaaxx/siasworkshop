package com.siaws.siawsmod.gametest;

import java.util.List;
import java.util.UUID;

import com.siaws.siawsmod.content.agents.AgentDecisionLoop;
import com.siaws.siawsmod.content.agents.AgentDecisionLoop.ProgressWatchdog;
import com.siaws.siawsmod.content.agents.AgentWorldData;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;
import com.siaws.siawsmod.content.agents.build.Blueprint;
import com.siaws.siawsmod.content.agents.build.BlueprintLibrary;
import com.siaws.siawsmod.content.agents.build.BuildQueue;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.init.SiasWorkshopEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Game tests for the executor wart-polish initiative (2026-10-20): the impossible-task
 * terminal (consecutive unroutable releases), the progress-vs-goal watchdog, multi-chest
 * withdrawOnce, and the self-collision/suffocation/obstruction give-up guards. Board-level
 * and watchdog tests are pure (no world); loop tests drive
 * {@link AgentDecisionLoop#runOnce} per tick with the same key-equals filter the other
 * loop tests use, so a shared GameTest world cannot divert the agent onto another test's
 * open tasks.
 */
@GameTestHolder("siasworkshop")
public final class AgentsExecutorGameTest {
    private AgentsExecutorGameTest() {}

    // --- wart 1: impossible-task terminal -----------------------------------------

    @GameTest(template = "empty")
    public static void repeatedUnroutableReleasesTerminalFailTask(GameTestHelper helper) {
        TaskBoard board = new TaskBoard();
        long now = 1000;
        String key = "build:mazons:-20,86,0";
        BoardTask task = board.raiseIfAbsent(key, BoardTask.Type.BUILD,
                "blueprint=mazons anchor=-20,86,0", now);
        UUID agent = UUID.randomUUID();

        for (int lap = 1; lap <= TaskBoard.UNREACHABLE_RELEASE_THRESHOLD; lap++) {
            helper.assertTrue(board.claim(task.id(), agent, 100, now + lap),
                    "task should be claimable before lap " + lap + ": " + board.tasks());
            boolean released = board.release(task.id(), agent, "unroutable", now + lap + 1);
            helper.assertTrue(released, "claimant release should succeed on lap " + lap);
            BoardTask current = board.tasks().get(0);
            if (lap < TaskBoard.UNREACHABLE_RELEASE_THRESHOLD) {
                helper.assertTrue(current.status() == BoardTask.Status.OPEN,
                        "lap " + lap + " should re-open the task: " + current);
                helper.assertTrue(board.releaseFailures(key) != null
                                && board.releaseFailures(key).consecutive() == lap
                                && "unroutable".equals(board.releaseFailures(key).lastClass()),
                        "lap " + lap + " should advance the streak: " + board.releaseFailures(key));
            }
        }
        BoardTask failed = board.tasks().get(0);
        helper.assertTrue(failed.status() == BoardTask.Status.FAILED,
                "third unroutable release should terminal-fail the task: " + failed);
        helper.assertTrue("unreachable".equals(failed.failureReason()),
                "terminal reason should be unreachable: " + failed.failureReason());
        helper.assertTrue(board.releaseFailures(key) == null,
                "spent streak should be dropped with the task: " + board.releaseFailures(key));
        helper.assertTrue(!board.claim(failed.id(), agent, 100, now + 99),
                "terminal-failed task must not be claimable again");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void transientReleaseResetsUnroutableStreak(GameTestHelper helper) {
        TaskBoard board = new TaskBoard();
        long now = 1000;
        String key = "comfort:light:" + UUID.randomUUID();
        BoardTask task = board.raiseIfAbsent(key, BoardTask.Type.COMFORT_LIGHT, "dark", now);
        UUID agent = UUID.randomUUID();

        board.claim(task.id(), agent, 100, now);
        board.release(task.id(), agent, "unroutable", now + 1);
        helper.assertTrue(board.releaseFailures(key).consecutive() == 1, "first unroutable release");

        board.claim(task.id(), agent, 100, now + 2);
        board.release(task.id(), agent, null, now + 3); // transient (restock/world-change)
        helper.assertTrue(board.releaseFailures(key) == null,
                "a transient release must reset the streak: " + board.releaseFailures(key));
        helper.assertTrue(board.tasks().get(0).status() == BoardTask.Status.OPEN,
                "transient release still re-opens the task");

        board.claim(task.id(), agent, 100, now + 4);
        board.release(task.id(), agent, "unroutable", now + 5);
        helper.assertTrue(board.releaseFailures(key).consecutive() == 1,
                "streak restarts from 1 after the reset, not 2: " + board.releaseFailures(key));
        helper.succeed();
    }

    // --- wart 2: progress-vs-goal watchdog ----------------------------------------

    @GameTest(template = "empty")
    public static void watchdogTripsOnMotionWithoutProgress(GameTestHelper helper) {
        ProgressWatchdog watch = new ProgressWatchdog();
        BlockPos goal = new BlockPos(0, 0, 0);
        // Arrival-circling: constant motion, every sample exactly 5 blocks out — the old
        // position-identical watchdog never saw this as stuck. The first sample on a fresh
        // goal resets (new baseline), so cycle i reports i.
        Vec3[] circle = {
                new Vec3(5, 0, 0), new Vec3(0, 0, 5),
                new Vec3(-5, 0, 0), new Vec3(0, 0, -5), new Vec3(5, 0, 0)};
        for (int i = 0; i < circle.length; i++) {
            int cycles = watch.record(goal, circle[i]);
            helper.assertTrue(cycles == i, "circling sample " + i + " should report " + i
                    + " no-progress cycles: " + cycles);
        }
        helper.assertTrue(watch.cycles() == circle.length - 1,
                "circling must accumulate no-progress cycles");

        int improved = watch.record(goal, new Vec3(4, 0, 0)); // really closer
        helper.assertTrue(improved == 0 && watch.cycles() == 0,
                "real progress must reset the streak: " + watch.cycles());

        watch.record(goal, new Vec3(4, 0, 0)); // unmoving, no progress
        helper.assertTrue(watch.cycles() == 1, "standing still must also count as no-progress");

        // Slow walking DOES count as progress: 0.05-block steps at 4 blocks out keep
        // shrinking the distance, so a walking agent must never accumulate no-progress cycles.
        watch.reset();
        for (int step = 0; step < 10; step++) {
            int cycles = watch.record(goal, new Vec3(4.0 - step * 0.05, 0, 0));
            helper.assertTrue(cycles == 0, "walking tick " + step + " tripped the watchdog: "
                    + cycles);
        }

        watch.record(new BlockPos(100, 0, 0), new Vec3(99, 0, 0)); // goal changed
        helper.assertTrue(watch.cycles() == 0, "a new goal must reset the streak");
        helper.succeed();
    }

    // --- wart 3: multi-chest withdrawOnce ------------------------------------------

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void buildWithdrawsAcrossMultipleChests(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Row runs along +z so the chests (at +x) never overlap a blueprint cell — the
        // first version put a chest INSIDE the row and the place loop correctly gave up
        // on it as an obstruction.
        Blueprint row = new Blueprint("wartmultihest", new Vec3i(1, 1, 3), List.of(
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 0, 0)),
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 0, 1)),
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 0, 2))));
        BlueprintLibrary.INSTANCE.registerForTest(row);

        BlockPos anchor = helper.absolutePos(new BlockPos(4, 1, 4));
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(3, 1, 4));
        // First chest in scan order is UNSTOCKED for stone (dirt only); the stone lives in
        // the chest behind it — invisible to the old withdrawOnce, which stopped at the
        // first container it found (SEQ-026).
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        helper.setBlock(new BlockPos(6, 1, 4), Blocks.CHEST);
        stockChest(helper, new BlockPos(5, 1, 4), new ItemStack(Blocks.DIRT.asItem(), 8));
        stockChest(helper, new BlockPos(6, 1, 4), new ItemStack(Items.STONE, 8));

        BoardTask task = BuildQueue.enqueue(level, "wartmultihest", anchor);
        helper.assertTrue(task != null && task.status() == BoardTask.Status.OPEN,
                "build task should be raised");
        // Direct claim — NOT a decide pass: runDecisions would offer this key to every idle
        // agent in the SHARED gametest world (see cleanup()'s javadoc), and sibling tests
        // spawn agents before this test's first tick.
        claimFor(helper, level, agent, task);

        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) {
                return;
            }
            AgentDecisionLoop.runExecutionPass(level);
            BoardTask current = boardTaskByKey(level, task.key());
            if (current != null && current.status() == BoardTask.Status.DONE) {
                finished[0] = true;
                helper.assertTrue(level.getBlockState(anchor).is(Blocks.STONE)
                                && level.getBlockState(anchor.south()).is(Blocks.STONE)
                                && level.getBlockState(anchor.south().south()).is(Blocks.STONE),
                        "all three row blocks should be placed across the two chests");
                cleanup(level, agent, current);
                helper.succeed();
            } else if (current != null && current.status() == BoardTask.Status.FAILED) {
                helper.fail("task must not fail with stocked chests in range: " + current);
            }
        });
    }

    // --- wart 4: self-collision, suffocation, obstruction give-up -------------------

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void buildNeverPlacesIntoAgentsOwnCell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Both cells of the pillar are the agent's own feet/head cells once it stands at
        // the anchor — placing suffocating stone there is exactly the live 12:06:57
        // entombment. The guard must refuse both cells, and one executor pass must leave
        // the agent unharmed.
        Blueprint pillarAtAgent = new Blueprint("wartselfcell", new Vec3i(1, 2, 1), List.of(
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 0, 0)),
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 1, 0))));
        BlueprintLibrary.INSTANCE.registerForTest(pillarAtAgent);

        BlockPos anchor = helper.absolutePos(new BlockPos(4, 1, 4));
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(4, 1, 4)); // inside the cells
        agent.getInventory().setItem(0, new ItemStack(Items.STONE, 8));

        BoardTask task = BuildQueue.enqueue(level, "wartselfcell", anchor);
        helper.assertTrue(task != null, "build task should be raised");
        claimFor(helper, level, agent, task);

        // Guard contract, pinned directly — pure function of (level, state, agent, pos).
        BlockState stone = Blocks.STONE.defaultBlockState();
        helper.assertTrue(AgentDecisionLoop.placementEntombsAgent(level, stone, agent, anchor),
                "stone at the agent's feet cell must count as entombment");
        helper.assertTrue(AgentDecisionLoop.placementEntombsAgent(level, stone, agent, anchor.above()),
                "stone at the agent's head cell must count as entombment");
        BlockPos clear = anchor.offset(3, 0, 0);
        helper.assertFalse(AgentDecisionLoop.placementEntombsAgent(level, stone, agent, clear),
                "stone three blocks away must be allowed");
        helper.assertFalse(AgentDecisionLoop.placementEntombsAgent(
                        level, Blocks.TORCH.defaultBlockState(), agent, anchor),
                "a torch at the agent's feet cell is the normal light case, not entombment");

        // One executor pass, asserted atomically in the same body: the cells must still be
        // air and the agent unharmed. A per-tick assert would race neighbor tests writing
        // into the shared batch world (the template is a 3x3x3 air cube — no isolation).
        helper.assertTrue(level.getBlockState(anchor).isAir()
                        && level.getBlockState(anchor.above()).isAir(),
                "blueprint cells should start air");
        AgentDecisionLoop.runExecutionPass(level);
        helper.assertTrue(level.getBlockState(anchor).isAir()
                        && level.getBlockState(anchor.above()).isAir(),
                "one pass must place nothing into the agent's own cells: feet="
                        + level.getBlockState(anchor) + " head=" + level.getBlockState(anchor.above()));
        helper.assertTrue(agent.getHealth() == agent.getMaxHealth(),
                "agent must not take suffocation damage: " + agent.getHealth());

        BoardTask current = boardTaskByKey(level, task.key());
        helper.assertTrue(current != null && current.status() == BoardTask.Status.CLAIMED,
                "task should stay claimed (placements skipped, nothing failed): " + current);
        cleanup(level, agent, current);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void buildGiveUpOnNonReplaceableObstruction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Blueprint single = new Blueprint("wartobstruct", new Vec3i(1, 1, 1), List.of(
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 0, 0))));
        BlueprintLibrary.INSTANCE.registerForTest(single);

        BlockPos anchor = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.OAK_LEAVES); // blueprint cell blocked
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(2, 1, 4)); // 2 blocks out: "arrived"
        agent.getInventory().setItem(0, new ItemStack(Items.STONE, 4));

        BoardTask task = BuildQueue.enqueue(level, "wartobstruct", anchor);
        helper.assertTrue(task != null, "build task should be raised");
        claimFor(helper, level, agent, task);

        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) {
                return;
            }
            AgentDecisionLoop.runExecutionPass(level);
            BoardTask current = boardTaskByKey(level, task.key());
            if (current != null && current.status() == BoardTask.Status.FAILED) {
                finished[0] = true;
                helper.assertTrue(current.failureReason() != null
                                && current.failureReason().startsWith("obstructed:"),
                        "give-up reason should be obstructed:<pos>: " + current.failureReason());
                helper.assertTrue(level.getBlockState(anchor).is(Blocks.OAK_LEAVES),
                        "v1 never breaks blocks — the leaves must remain");
                cleanup(level, agent, current);
                helper.succeed();
            }
        });
        helper.runAfterDelay(199, () -> helper.fail("task never gave up on the obstructed cell"));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void suffocatingAgentBailsOutOfTask(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Blueprint single = new Blueprint("wartsuffocate", new Vec3i(1, 1, 1), List.of(
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 0, 0))));
        BlueprintLibrary.INSTANCE.registerForTest(single);

        BlockPos anchor = helper.absolutePos(new BlockPos(4, 1, 4));
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(3, 1, 4));
        agent.getInventory().setItem(0, new ItemStack(Items.STONE, 4));

        BoardTask task = BuildQueue.enqueue(level, "wartsuffocate", anchor);
        helper.assertTrue(task != null, "build task should be raised");
        claimFor(helper, level, agent, task);

        // All in one atomic body: a decide pass anywhere in here could hand this just-
        // released OPEN task to any idle agent in the shared world (see cleanup()'s javadoc),
        // and the production tick loop decides unfiltered on tick boundaries.
        helper.setBlock(new BlockPos(3, 2, 4), Blocks.STONE); // entomb the agent's head
        AgentDecisionLoop.runExecutionPass(level); // execution only: bail, no decisions

        BoardTask after = boardTaskByKey(level, task.key());
        helper.assertTrue(after != null && after.status() == BoardTask.Status.OPEN
                        && after.claimantUuid() == null,
                "suffocation must release the claim (transient, no streak): " + after);
        helper.assertTrue(agent.isAlive(), "agent should still be alive at bail time");

        helper.setBlock(new BlockPos(3, 2, 4), Blocks.AIR); // recovery: user breaks the block
        helper.assertTrue(agent.isAlive(), "agent should survive once the block is gone");
        cleanup(level, agent, after);
        helper.succeed();
    }

    // --- helpers ----------------------------------------------------------------

    private static WorkshopAgentEntity spawnAgent(GameTestHelper helper, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        WorkshopAgentEntity agent = new WorkshopAgentEntity(SiasWorkshopEntities.WORKSHOP_AGENT.get(), level);
        BlockPos spawn = helper.absolutePos(relative);
        agent.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(agent);
        agent.goalSelector.getAvailableGoals().clear(); // deterministic loop tests: no random stroll
        helper.assertFalse(agent.isRemoved(), "agent failed to spawn");
        return agent;
    }

    private static void stockChest(GameTestHelper helper, BlockPos relative, ItemStack stack) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        helper.assertTrue(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock,
                "expected a chest at " + pos);
        net.minecraft.world.level.block.entity.BlockEntity entity = level.getBlockEntity(pos);
        helper.assertTrue(entity instanceof Container, "chest block entity should be a Container");
        ((Container) entity).setItem(0, stack);
    }

    /**
     * Claims {@code task} for {@code agent} directly on the board, bypassing decide(). The
     * executor tests pin EXECUTION behavior; a decide pass would offer the key to every idle
     * agent in the shared gametest world (see {@link #cleanup(ServerLevel,
     * WorkshopAgentEntity, BoardTask)}'s javadoc) and sibling tests spawn agents before this
     * test's first tick. The lease renews every execution pass while the executor works.
     */
    private static void claimFor(GameTestHelper helper, ServerLevel level, WorkshopAgentEntity agent, BoardTask task) {
        helper.assertTrue(AgentWorldData.board(level).claim(task.id(), agent.getUUID(),
                        Config.AGENTS_TASK_LEASE_TICKS.get(), level.getGameTime()),
                "test setup should claim the task: " + task.key());
        agent.setCurrentTaskLabel(task.key());
    }

    /**
     * Removes the test's leftovers from the SHARED gametest world: the production tick loop
     * ({@code agents.executionLoopEnabled = true} in the test world) decides UNFILTERED every
     * interval, so a stray idle agent gets claimed onto any later batch's open task and a stray
     * OPEN task absorbs any later idle agent — both poison unrelated tests in later batches
     * (proven by a full-suite red run when these cleanups were absent). Mirrors the existing
     * {@code agent.discard()} in {@code AgentsMcpGameTest}.
     */
    private static void cleanup(ServerLevel level, WorkshopAgentEntity agent, BoardTask task) {
        TaskBoard board = AgentWorldData.board(level);
        board.release(task.id(), agent.getUUID(), null, level.getGameTime());
        board.clearIfOpen(task.key());
        agent.discard();
    }

    private static BoardTask boardTaskByKey(ServerLevel level, String key) {
        for (BoardTask task : AgentWorldData.board(level).tasks()) {
            if (task.key().equals(key)) {
                return task;
            }
        }
        return null;
    }
}
