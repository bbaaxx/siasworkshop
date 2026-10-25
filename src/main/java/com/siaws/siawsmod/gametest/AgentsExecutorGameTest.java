package com.siaws.siawsmod.gametest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.siaws.siawsmod.content.agents.AgentDecisionLoop;
import com.siaws.siawsmod.content.agents.AgentDecisionLoop.ProgressWatchdog;
import com.siaws.siawsmod.content.agents.AgentWorldData;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;
import com.siaws.siawsmod.content.agents.build.Blueprint;
import com.siaws.siawsmod.content.agents.build.BlueprintLibrary;
import com.siaws.siawsmod.content.agents.build.BuildQueue;
import com.siaws.siawsmod.content.agents.knowledge.AgentKnowledge;
import com.siaws.siawsmod.content.agents.primitives.BreakSupport;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.ComfortDetectors;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;
import com.siaws.siawsmod.content.mcp.McpTools;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.init.SiasWorkshopEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
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
        // A PROTECTED cell (chest = block entity): the break primitive refuses, so the
        // 20-cycle give-up path must still terminal-fail the task. (Pre-break era this
        // test used leaves; leaves are agent-breakable now — the give-up class shrank
        // but did not disappear.)
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.CHEST);
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
                helper.assertTrue(level.getBlockState(anchor).is(Blocks.CHEST),
                        "protected blocks must survive — agents never break containers");
                cleanup(level, agent, current);
                helper.succeed();
            }
        });
        helper.runAfterDelay(199, () -> helper.fail("task never gave up on the protected obstruction"));
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

    // --- build staging + self-extrication + knowledge ledger (live test 2026-10-22) ---

    /**
     * Authored ground for the staging tests: the batch world is REAL worldgen (the terrain
     * level varies per structure position — a one-block dip across ten blocks was observed
     * live), so anything geometry-sensitive must stand on a platform the test controls.
     * Dirt at rel y=0: top surface rel y=1, feet at rel y=1, free air above.
     */
    private static void dirtPlatform(GameTestHelper helper) {
        for (int dx = 4; dx <= 10; dx++) {
            for (int dz = 4; dz <= 10; dz++) {
                helper.setBlock(new BlockPos(dx, 0, dz), Blocks.DIRT);
            }
        }
    }

    @GameTest(template = "empty", batch = "agentsBuildStaging", timeoutTicks = 1000)
    public static void buildExtricatesItselfAndCompletesFootprint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        dirtPlatform(helper);
        // 3×3×3 solid cube whose bottom layer includes the agent's own feet cell — the live
        // 2026-10-22 defect: the agent built around itself, skipped its own cells forever,
        // and the task could never complete. The executor must walk out to the staging
        // cell and place every cell remotely, including the ones it stood in.
        List<Blueprint.Placement> cube = new ArrayList<>();
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    cube.add(new Blueprint.Placement("minecraft:stone", new BlockPos(dx, dy, dz)));
                }
            }
        }
        BlueprintLibrary.INSTANCE.registerForTest(new Blueprint("wartextricate", new Vec3i(3, 3, 3), cube));

        BlockPos anchor = helper.absolutePos(new BlockPos(6, 1, 6));
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(6, 2, 6)); // settles to feet (6,1,6)
        helper.setBlock(new BlockPos(4, 1, 6), Blocks.CHEST);
        stockChest(helper, new BlockPos(4, 1, 6), new ItemStack(Items.STONE, 27));

        BoardTask task = BuildQueue.enqueue(level, "wartextricate", anchor);
        helper.assertTrue(task != null, "build task should be raised");
        claimFor(helper, level, agent, task);

        // Boxed-in contract, asserted atomically: while the agent's body intersects the
        // footprint the executor must refuse to place (the live defect placed everything
        // around itself and skipped its own cells forever). Gametest entities cannot
        // path at the far-flung random positions of the batch world (navigation yields
        // no path there — vanilla locomotion is exercised by live tests), so the one-step
        // walk to the staging cell is simulated with a moveTo; placement itself is
        // remote and fully exercised below.
        Set<BlockPos> footprint = new HashSet<>();
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    footprint.add(anchor.offset(dx, dy, dz));
                }
            }
        }
        AgentDecisionLoop.runExecutionPass(level);
        AgentDecisionLoop.runExecutionPass(level);
        helper.assertTrue(level.getBlockState(anchor).isAir()
                        && boardTaskByKey(level, task.key()).status() == BoardTask.Status.CLAIMED,
                "a boxed-in agent must step out before placing anything");
        BlockPos staging = AgentDecisionLoop.nearestStagingCell(level, anchor, footprint, 6);
        helper.assertTrue(staging != null && !footprint.contains(staging)
                && !footprint.contains(staging.above()),
                "staging must be a standable cell outside the footprint: " + staging);
        agent.moveTo(staging.getX() + 0.5, staging.getY(), staging.getZ() + 0.5, 0.0F, 0.0F);

        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) {
                return;
            }
            AgentDecisionLoop.runExecutionPass(level);
            BoardTask current = boardTaskByKey(level, task.key());
            if (current != null && current.status() == BoardTask.Status.DONE) {
                finished[0] = true;
                for (int dx = 0; dx < 3; dx++) {
                    for (int dy = 0; dy < 3; dy++) {
                        for (int dz = 0; dz < 3; dz++) {
                            helper.assertTrue(
                                    level.getBlockState(anchor.offset(dx, dy, dz)).is(Blocks.STONE),
                                    "footprint cell " + dx + "," + dy + "," + dz + " should be stone");
                        }
                    }
                }
                cleanup(level, agent, current);
                helper.succeed();
            } else if (current != null && current.status() == BoardTask.Status.FAILED) {
                helper.fail("self-entombed build must complete, not fail: " + current);
            }
        });
    }

    @GameTest(template = "empty", batch = "agentsBuildStaging", timeoutTicks = 400)
    public static void buildStagesOnGroundBelowFloatingAnchor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        dirtPlatform(helper);
        // The anchor floats three blocks above the only ground — the live -20,86,0
        // unreachable cube. The old 2-block 3D arrival against the anchor corner could
        // never be satisfied there; the executor must stage on the ground below and place
        // remotely (guard radius, not agent reach, bounds placements).
        BlueprintLibrary.INSTANCE.registerForTest(new Blueprint("wartstage", new Vec3i(1, 1, 1), List.of(
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 0, 0)))));

        BlockPos anchor = helper.absolutePos(new BlockPos(7, 4, 7));
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(5, 2, 5));
        agent.getInventory().setItem(0, new ItemStack(Items.STONE, 4));

        // The staging cell must exist BEYOND the old 2-block 3D arrival radius — that is
        // the whole point for an anchor floating three blocks above the only ground (the
        // live -20,86,0 unreachable cube). The walk itself is simulated (gametest
        // entities cannot path at batch-world positions); placement from staging is
        // remote and fully exercised by the poll below.
        BlockPos staging = AgentDecisionLoop.nearestStagingCell(level, anchor, Set.of(anchor, anchor.above()), 6);
        helper.assertTrue(staging != null && staging.distSqr(anchor) > 4,
                "staging must exist beyond the old 2-block arrival radius: " + staging);
        agent.moveTo(staging.getX() + 0.5, staging.getY(), staging.getZ() + 0.5, 0.0F, 0.0F);

        BoardTask task = BuildQueue.enqueue(level, "wartstage", anchor);
        helper.assertTrue(task != null, "build task should be raised");
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
                helper.assertTrue(level.getBlockState(anchor).is(Blocks.STONE),
                        "the floating anchor cell should be built from the staging ground");
                cleanup(level, agent, current);
                helper.succeed();
            } else if (current != null && current.status() == BoardTask.Status.FAILED) {
                helper.fail("floating-anchor build must stage, not fail: " + current);
            }
        });
    }

    @GameTest(template = "empty", batch = "agentsBuildStaging", timeoutTicks = 600)
    public static void materialsShortParksTaskInKnowledgeLedger(GameTestHelper helper) {        ServerLevel level = helper.getLevel();
        dirtPlatform(helper);
        BlueprintLibrary.INSTANCE.registerForTest(new Blueprint("wartledger", new Vec3i(1, 1, 1), List.of(
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 0, 0)))));

        BlockPos anchor = helper.absolutePos(new BlockPos(6, 1, 6));
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(5, 2, 5));
        // no chest and an empty inventory — the palette item is unobtainable

        BoardTask task = BuildQueue.enqueue(level, "wartledger", anchor);
        helper.assertTrue(task != null, "build task should be raised");
        claimFor(helper, level, agent, task);

        // One pass parks the task: staging reached, withdraw finds nothing, firstShortItem
        // fires. Asserted atomically in one body — a blocked task is also invisible to the
        // cross-batch production decide, so nothing can pick it up mid-assert.
        AgentDecisionLoop.runExecutionPass(level);
        BoardTask parked = boardTaskByKey(level, task.key());
        helper.assertTrue(parked != null && parked.status() == BoardTask.Status.OPEN
                        && parked.claimantUuid() == null,
                "materials-short build must be released OPEN, not failed: " + parked);
        List<AgentKnowledge.MaterialNeed> needs = AgentWorldData.knowledge(level).blockedNeeds(task.id());
        helper.assertTrue(needs.size() == 1
                        && "minecraft:stone".equals(needs.get(0).itemId())
                        && needs.get(0).quantityNeeded() == 1
                        && "BLOCKED".equals(needs.get(0).status()),
                "ledger should hold the stone deficit: " + needs);
        helper.assertTrue(AgentDecisionLoop.openTasks(level, AgentWorldData.board(level)).stream()
                        .noneMatch(open -> open.key().equals(task.key())),
                "a blocked task must not be claimable via openTasks");

        // Restock the station chest, re-check, reclaim — the build then completes.
        helper.setBlock(new BlockPos(4, 1, 6), Blocks.CHEST);
        stockChest(helper, new BlockPos(4, 1, 6), new ItemStack(Items.STONE, 1));
        int resolved = AgentWorldData.knowledge(level).recheckBlocked(level, AgentWorldData.board(level),
                level.getGameTime());
        helper.assertTrue(resolved == 1 && AgentWorldData.knowledge(level).blockedNeeds(task.id()).isEmpty(),
                "restock should resolve the deficit: resolved=" + resolved);
        claimFor(helper, level, agent, parked);

        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) {
                return;
            }
            AgentDecisionLoop.runExecutionPass(level);
            BoardTask current = boardTaskByKey(level, task.key());
            if (current != null && current.status() == BoardTask.Status.DONE) {
                finished[0] = true;
                helper.assertTrue(level.getBlockState(anchor).is(Blocks.STONE),
                        "the restocked build should complete");
                cleanup(level, agent, current);
                helper.succeed();
            } else if (current != null && current.status() == BoardTask.Status.FAILED) {
                helper.fail("restocked build must not fail: " + current);
            }
        });
    }

    // --- COMFORT_SHELTER executor ---------------------------------------------------

    @GameTest(template = "empty", batch = "agentsExecutorShelter", timeoutTicks = 800)
    public static void shelterExecutorBuildsCanopyAndAutoCompletes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(13000); // night — the shelter detector is night-gated
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // The 3×3×3 template core is wrapped in a shell of barrier (vanilla GameTest
        // frame) covering roughly rel -1..3 — anchoring the canopy there made the roof
        // cells unplaceable barrier and the approach unwalkable. Camp the player well
        // outside the shell, on open superflat ground.
        BlockPos feet = helper.absolutePos(new BlockPos(10, 1, 10));
        player.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(13, 1, 10));
        agent.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 16));
        agent.getInventory().setItem(1, new ItemStack(Items.RED_BED, 1));
        String key = "comfort:shelter:" + player.getUUID();

        // The mock player only lands in level.players() a tick after join — raising the
        // deficit from the body would let the orphan cleaner eat it before that. Arm at
        // body time, raise + claim in the delayed phase.
        boolean[] armed = {false};
        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (!armed[0] || finished[0]) {
                return;
            }
            AgentDecisionLoop.runExecutionPass(level);
            if (level.getGameTime() % 20 == 0) {
                ComfortDetectors.runOnce(level); // the detector is the completion oracle
            }
            BoardTask current = boardTaskByKey(level, key);
            if (current != null && current.status() == BoardTask.Status.DONE) {
                finished[0] = true;
                BlockPos anchor = player.blockPosition();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        helper.assertTrue(level.getBlockState(anchor.offset(dx, 2, dz)).is(Blocks.OAK_PLANKS),
                                "roof cell missing at (" + dx + ",2," + dz + "): "
                                        + level.getBlockState(anchor.offset(dx, 2, dz)));
                    }
                }
                boolean bedFound = false;
                for (int dy = 0; dy >= -1 && !bedFound; dy--) {
                    for (int[] c : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                        if (level.getBlockState(anchor.offset(c[0], dy, c[1])).getBlock() instanceof BedBlock) {
                            bedFound = true;
                            break;
                        }
                    }
                }
                helper.assertTrue(bedFound, "bed should be placed beside the player (anchor=" + anchor
                        + "): " + level.getBlockState(anchor.offset(2, -1, 0)));
                helper.assertTrue(
                        ComfortDetectors.isSheltered(level, player, Config.AGENTS_SHELTER_RADIUS.get()),
                        "the detector itself must see the player as sheltered at the end");
                level.getServer().getPlayerList().remove(player);
                cleanup(level, agent, current);
                helper.succeed();
            }
        });
        helper.runAfterDelay(40, () -> {
            try {
                ComfortDetectors.runOnce(level); // raises the deficit (night + exposed, no bed)
                BoardTask task = AgentWorldData.board(level).activeTask(key);
                helper.assertTrue(task != null && task.status() == BoardTask.Status.OPEN,
                        "night exposure should raise the shelter deficit: "
                                + AgentWorldData.board(level).tasks());
                claimFor(helper, level, agent, task);
                armed[0] = true;
            } catch (Throwable t) {
                level.getServer().getPlayerList().remove(player);
                AgentWorldData.board(level).clearIfOpen(key);
                agent.discard();
                throw t;
            }
        });
        helper.runAfterDelay(799, () -> {
            BoardTask current = boardTaskByKey(level, key);
            BlockPos anchor = player.blockPosition();
            StringBuilder roof = new StringBuilder();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    roof.append('(').append(dx).append(',').append(dz).append(")=")
                            .append(BuiltInRegistries.BLOCK.getKey(
                                    level.getBlockState(anchor.offset(dx, 2, dz)).getBlock()).getPath())
                            .append(' ');
                }
            }
            // Clean up even on the failure path — a stray agent/player poisons later batches.
            level.getServer().getPlayerList().remove(player);
            AgentWorldData.board(level).clearIfOpen(key);
            agent.discard();
            helper.fail("shelter task never auto-completed: task=" + current
                    + " agentPos=" + agent.blockPosition()
                    + " releaseFailures=" + AgentWorldData.board(level).releaseFailures(key)
                    + " invPlanks=" + countOf(agent, Items.OAK_PLANKS)
                    + " roof: " + roof
                    + " bedCell=" + level.getBlockState(anchor.offset(2, 0, 0)));
        });
    }

    @GameTest(template = "empty")
    public static void shelterExecutorReleasesWhenPlayerOffline(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(13000);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos feet = helper.absolutePos(new BlockPos(0, 1, 0));
        player.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(2, 1, 0));
        String key = "comfort:shelter:" + player.getUUID();

        // Delayed phase: the mock player only lands in level.players() a tick after join,
        // and the executor's player lookup needs the real join path to have run.
        helper.runAfterDelay(40, () -> {
            try {
                BoardTask task = AgentWorldData.board(level).raiseIfAbsent(key,
                        BoardTask.Type.COMFORT_SHELTER, "exposed at night for tester", level.getGameTime());
                claimFor(helper, level, agent, task);
                level.getServer().getPlayerList().remove(player);

                AgentDecisionLoop.runExecutionPass(level);
                BoardTask after = boardTaskByKey(level, key);
                helper.assertTrue(after != null && after.status() == BoardTask.Status.OPEN
                                && after.claimantUuid() == null,
                        "a gone player must release the claim transiently: " + after);
                helper.assertTrue(AgentWorldData.board(level).releaseFailures(key) == null,
                        "transient release must not start an unreachable streak: "
                                + AgentWorldData.board(level).releaseFailures(key));
                helper.assertTrue(agent.isAlive(), "agent should be unharmed");
                AgentWorldData.board(level).clearIfOpen(key);
                agent.discard();
                helper.succeed();
            } catch (Throwable t) {
                level.getServer().getPlayerList().remove(player);
                AgentWorldData.board(level).clearIfOpen(key);
                agent.discard();
                throw t;
            }
        });
    }

    @GameTest(template = "empty")
    public static void shelterPlacementRefusesOccupiedCells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos feet = helper.absolutePos(new BlockPos(0, 1, 0));
        player.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);

        helper.assertTrue(AgentDecisionLoop.placementIntersectsEntity(player, feet),
                "the player's own cell must intersect their bbox");
        helper.assertTrue(AgentDecisionLoop.placementIntersectsEntity(player, feet.above()),
                "the player's head cell must intersect their bbox");
        helper.assertFalse(AgentDecisionLoop.placementIntersectsEntity(player, feet.offset(3, 0, 0)),
                "a cell three blocks away must be free");
        helper.assertFalse(AgentDecisionLoop.placementIntersectsEntity(player, feet.offset(0, 2, 0)),
                "the canopy cell two blocks up must be free for a standing player");

        level.getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    // --- break primitive (action-surface slice 1, 2026-10-23) -----------------------

    @GameTest(template = "empty", batch = "agentsBuildStaging", timeoutTicks = 400)
    public static void breakingGuardrailsTeachReasons(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        dirtPlatform(helper);
        BlockPos home = helper.absolutePos(new BlockPos(6, 1, 6));
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(6, 2, 6)); // settles beside the cases
        try {
            BlockPos air = helper.absolutePos(new BlockPos(5, 1, 6));
            BlockPos leaves = helper.absolutePos(new BlockPos(6, 1, 7));
            BlockPos dirt = helper.absolutePos(new BlockPos(6, 1, 8));
            BlockPos stone = helper.absolutePos(new BlockPos(7, 1, 6));
            BlockPos ironOre = helper.absolutePos(new BlockPos(7, 1, 7));
            BlockPos chest = helper.absolutePos(new BlockPos(7, 1, 8));
            BlockPos far = helper.absolutePos(new BlockPos(40, 1, 6)); // beyond the 32-block guard radius
            helper.setBlock(new BlockPos(6, 1, 7), Blocks.OAK_LEAVES);
            helper.setBlock(new BlockPos(6, 1, 8), Blocks.DIRT);
            helper.setBlock(new BlockPos(7, 1, 6), Blocks.STONE);
            helper.setBlock(new BlockPos(7, 1, 7), Blocks.IRON_ORE);
            helper.setBlock(new BlockPos(7, 1, 8), Blocks.CHEST);
            helper.setBlock(new BlockPos(40, 1, 6), Blocks.DIRT);

            helper.assertTrue(reason(agent, level, air).equals("no_block"),
                    "air must refuse with no_block: " + reason(agent, level, air));
            helper.assertTrue(reason(agent, level, far).equals("too_far"),
                    "a block beyond the guard radius must refuse with too_far: " + reason(agent, level, far));
            helper.assertTrue(reason(agent, level, chest).equals("protected"),
                    "containers must refuse with protected: " + reason(agent, level, chest));
            helper.assertTrue(reason(agent, level, leaves) == null,
                    "leaves are hand-breakable: " + reason(agent, level, leaves));
            helper.assertTrue(reason(agent, level, dirt) == null,
                    "dirt is hand-breakable: " + reason(agent, level, dirt));
            helper.assertTrue(reason(agent, level, stone) == null,
                    "stone is hand-breakable (no drops without a pickaxe, vanilla-style): "
                            + reason(agent, level, stone));
            String oreVerdict = reason(agent, level, ironOre);
            helper.assertTrue(oreVerdict != null && oreVerdict.startsWith("needs_tool:stone_"),
                    "iron ore without any tool must ask for a stone pickaxe: " + oreVerdict);

            // And with the tool anywhere in the inventory the primitive equips it and allows.
            agent.getInventory().setItem(1, new ItemStack(Items.STONE_PICKAXE, 1));
            helper.assertTrue(BreakSupport.evaluate(level, agent, ironOre).allowed(),
                    "iron ore with a stone pickaxe in inventory must be allowed");
            helper.assertTrue(agent.getMainHandItem().is(Items.STONE_PICKAXE),
                    "the suitable tool must be swapped into the main hand: " + agent.getMainHandItem());
            helper.succeed();
        } finally {
            // a leaked agent poisons LATER batches via the unfiltered production decide
            agent.discard();
        }
    }

    @GameTest(template = "empty", batch = "agentsBuildStaging", timeoutTicks = 400)
    public static void breakingDropsLandInInventoryAndWearTool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        dirtPlatform(helper);
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(6, 2, 6));
        try {
            BlockPos dirt = helper.absolutePos(new BlockPos(6, 1, 7));
            helper.setBlock(new BlockPos(6, 1, 7), Blocks.DIRT);
            helper.assertTrue(BreakSupport.breakBlock(level, agent, dirt),
                    "dirt must break by hand");
            helper.assertTrue(level.getBlockState(dirt).isAir(), "the dirt cell must be air");
            helper.assertTrue(countOf(agent, Items.DIRT) > 0,
                    "the dirt drop must land in the agent's inventory");

            BlockPos ore = helper.absolutePos(new BlockPos(7, 1, 7));
            helper.setBlock(new BlockPos(7, 1, 7), Blocks.IRON_ORE);
            agent.getInventory().setItem(1, new ItemStack(Items.STONE_PICKAXE, 1));
            helper.assertTrue(BreakSupport.breakBlock(level, agent, ore),
                    "iron ore must break once a suitable pickaxe is equipped");
            helper.assertTrue(countOf(agent, Items.RAW_IRON) > 0,
                    "the raw iron drop must land in the agent's inventory");
            helper.assertTrue(agent.getMainHandItem().is(Items.STONE_PICKAXE)
                            && agent.getMainHandItem().getMaxDamage() - agent.getMainHandItem().getDamageValue() == 130,
                    "the used pickaxe must lose one durability: " + agent.getMainHandItem());
            helper.succeed();
        } finally {
            // a leaked agent poisons LATER batches through the unfiltered production decide
            agent.discard();
        }
    }

    @GameTest(template = "empty", batch = "agentsBuildStaging", timeoutTicks = 400)
    public static void buildClearsObstructionThenCompletes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        dirtPlatform(helper);
        BlueprintLibrary.INSTANCE.registerForTest(new Blueprint("wartclear", new Vec3i(1, 1, 1), List.of(
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 0, 0)))));

        BlockPos anchor = helper.absolutePos(new BlockPos(6, 1, 6));
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.OAK_LEAVES); // blueprint cell obstructed (the live leaves episode)
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(5, 2, 5));
        agent.getInventory().setItem(0, new ItemStack(Items.STONE, 1));

        BoardTask task = BuildQueue.enqueue(level, "wartclear", anchor);
        helper.assertTrue(task != null, "build task should be raised");
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
                helper.assertTrue(level.getBlockState(anchor).is(Blocks.STONE),
                        "the executor must clear the leaves then place the blueprint block");
                cleanup(level, agent, current);
                helper.succeed();
            } else if (current != null && current.status() == BoardTask.Status.FAILED) {
                helper.fail("clearable obstruction must not fail the build: " + current);
            }
        });
    }

    @GameTest(template = "empty", batch = "agentsBuildStaging", timeoutTicks = 400)
    public static void mcpBreakBlockToolActsAndTeaches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        dirtPlatform(helper);
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(6, 2, 6));
        var server = helper.getLevel().getServer();

        BlockPos leaves = helper.absolutePos(new BlockPos(6, 1, 7));
        BlockPos ironOre = helper.absolutePos(new BlockPos(7, 1, 7));
        BlockPos chest = helper.absolutePos(new BlockPos(7, 1, 8));
        BlockPos far = helper.absolutePos(new BlockPos(40, 1, 6));
        helper.setBlock(new BlockPos(6, 1, 7), Blocks.OAK_LEAVES);
        helper.setBlock(new BlockPos(7, 1, 7), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(7, 1, 8), Blocks.CHEST);
        helper.setBlock(new BlockPos(40, 1, 6), Blocks.DIRT);

        // Success: hand-breakable leaves.
        JsonObject broke = McpTools.dispatch(server, "break_block",
                args("agent", agent.getUUID().toString(), "pos", leaves.getX() + "," + leaves.getY() + "," + leaves.getZ()));
        helper.assertTrue(broke.has("broke"), "leaves should break: " + broke);
        helper.assertTrue(level.getBlockState(leaves).isAir(), "the cell must be air after the break");

        // Teaching rejects: the model reads these to plan the means.
        JsonObject needsTool = McpTools.dispatch(server, "break_block",
                args("agent", agent.getUUID().toString(), "pos", ironOre.getX() + "," + ironOre.getY() + "," + ironOre.getZ()));
        helper.assertTrue(needsTool.has("reject") && needsTool.get("reject").getAsString().contains("needs_tool:"),
                "iron ore without a tool must teach needs_tool: " + needsTool);
        JsonObject protectedReject = McpTools.dispatch(server, "break_block",
                args("agent", agent.getUUID().toString(), "pos", chest.getX() + "," + chest.getY() + "," + chest.getZ()));
        helper.assertTrue(protectedReject.has("reject") && protectedReject.get("reject").getAsString().contains("protected"),
                "chests must reject as protected: " + protectedReject);
        JsonObject tooFar = McpTools.dispatch(server, "break_block",
                args("agent", agent.getUUID().toString(), "pos", far.getX() + "," + far.getY() + "," + far.getZ()));
        helper.assertTrue(tooFar.has("reject") && tooFar.get("reject").getAsString().contains("too_far"),
                "distant blocks must reject as too_far: " + tooFar);
        JsonObject badArgs = McpTools.dispatch(server, "break_block",
                args("agent", agent.getUUID().toString(), "pos", "not-a-pos"));
        helper.assertTrue(badArgs.has("reject"), "malformed pos must reject: " + badArgs);

        agent.discard();
        helper.succeed();
    }

    private static String reason(WorkshopAgentEntity agent, ServerLevel level, BlockPos pos) {
        return BreakSupport.evaluate(level, agent, pos).reason();
    }

    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
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

    private static int countOf(WorkshopAgentEntity agent, net.minecraft.world.item.Item item) {
        return net.minecraft.world.ContainerHelper.clearOrCountMatchingItems(
                agent.getInventory(), stack -> stack.is(item), -1, true);
    }
}
