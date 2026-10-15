package com.siaws.siawsmod.content.agents.tasks;

import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.content.agents.AgentWorldData;
import com.siaws.siawsmod.content.agents.pool.AgentDecisionPool;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Deterministic comfort deficit detectors (design pillar 4): they decide THAT a fix is
 * needed — an LLM never guesses this — and raise singleton task-board entries for it.
 *
 * <ul>
 *   <li><b>Light</b>: air positions with a rigid floor and block light below the threshold
 *       (spawnable-dark spots) near a player raise {@code comfort:light:<uuid>}. A position in
 *       full sky light is never counted — block light alone is 0 in daylight, so the raw
 *       block-light check would flag every outdoor spot at noon (the design intent is
 *       spawnable-dark).</li>
 *   <li><b>Shelter</b> (night only): a player with no bed in range or exposed to the open
 *       sky raises {@code comfort:shelter:<uuid>}.</li>
 * </ul>
 *
 * <p>Runs every {@code agents.detectorIntervalTicks} server ticks (config), per
 * {@link ServerLevel}, gated by {@code agents.comfortDetectorsEnabled}. Each detector scan
 * reuses one {@link BlockPos.MutableBlockPos} and allocates nothing but counters in the
 * hot path. {@link #runOnce(ServerLevel)} is public so gametests can invoke a pass directly.
 */
public final class ComfortDetectors {
    private static int ticksUntilRun;

    private ComfortDetectors() {}

    /** Server tick hook, registered on the NeoForge event bus from the mod entrypoint. */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Config.AGENTS_COMFORT_DETECTORS_ENABLED.get()) {
            return;
        }
        if (--ticksUntilRun > 0) {
            return;
        }
        ticksUntilRun = Config.AGENTS_DETECTOR_INTERVAL_TICKS.get();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            runOnce(level);
        }
    }

    /** One full pass for one level: lease sweep, orphan cleanup, then both detectors for every player in it. */
    public static void runOnce(ServerLevel level) {
        long now = level.getGameTime();
        TaskBoard board = AgentWorldData.board(level);
        AgentDecisionPool pool = AgentWorldData.pool(level);
        int poolMaxEntries = Config.AGENTS_POOL_MAX_ENTRIES.get();

        board.sweepLeases(now);
        clearOrphanedComfortTasks(level, board);
        if (level.players().isEmpty()) {
            return;
        }
        int lightRadius = Config.AGENTS_LIGHT_SCAN_RADIUS.get();
        int lightThreshold = Config.AGENTS_LIGHT_THRESHOLD.get();
        int shelterRadius = Config.AGENTS_SHELTER_RADIUS.get();
        for (ServerPlayer player : level.players()) {
            BlockPos pos = player.blockPosition();
            runLightDetector(level, board, pool, poolMaxEntries, player, pos, now, lightRadius, lightThreshold);
            if (!level.isDay()) {
                runShelterDetector(level, board, pool, poolMaxEntries, player, pos, now, shelterRadius);
            }
        }
    }

    /** OPEN comfort tasks whose player left the level are stale — remove them (CLAIMED ones expire via lease). */
    private static void clearOrphanedComfortTasks(ServerLevel level, TaskBoard board) {
        for (BoardTask task : board.tasks()) {
            if (task.status() != BoardTask.Status.OPEN
                    || (task.type() != BoardTask.Type.COMFORT_LIGHT && task.type() != BoardTask.Type.COMFORT_SHELTER)) {
                continue;
            }
            boolean online = false;
            for (ServerPlayer player : level.players()) {
                if (task.key().endsWith(":" + player.getUUID())) {
                    online = true;
                    break;
                }
            }
            if (!online) {
                board.clearIfOpen(task.key());
            }
        }
    }

    private static void runLightDetector(ServerLevel level, TaskBoard board, AgentDecisionPool pool,
            int poolMaxEntries, ServerPlayer player, BlockPos center, long now, int radius, int threshold) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int deficit = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.getBlockState(cursor).isAir()) {
                        continue;
                    }
                    if (!Block.canSupportRigidBlock(level, cursor.move(Direction.DOWN))) {
                        continue;
                    }
                    cursor.move(Direction.UP);
                    if (!level.canSeeSky(cursor) && level.getBrightness(LightLayer.BLOCK, cursor) < threshold) {
                        deficit++;
                    }
                }
            }
        }
        String key = "comfort:light:" + player.getUUID();
        if (deficit > 0) {
            board.raiseIfAbsent(key, BoardTask.Type.COMFORT_LIGHT, deficit + " dark spots near " + player.getName().getString(), now);
        } else {
            board.clearIfOpen(key);
            board.autoCompleteClaimed(key, now, pool, poolMaxEntries);
        }
    }

    private static void runShelterDetector(ServerLevel level, TaskBoard board, AgentDecisionPool pool,
            int poolMaxEntries, ServerPlayer player, BlockPos center, long now, int radius) {
        boolean bedNearby = hasBedNearby(level, center, radius);
        // raw sky light is 15 for any column open to the sky at any time of day (StarLight stores
        // it without the night-time skydarken), so this is a true exposure check, not a daylight check
        boolean exposed = level.canSeeSky(center);
        String key = "comfort:shelter:" + player.getUUID();
        if (!bedNearby || exposed) {
            board.raiseIfAbsent(key, BoardTask.Type.COMFORT_SHELTER,
                    (exposed ? "exposed" : "no bed") + " at night for " + player.getName().getString(), now);
        } else {
            board.clearIfOpen(key);
            board.autoCompleteClaimed(key, now, pool, poolMaxEntries);
        }
    }

    private static boolean hasBedNearby(ServerLevel level, BlockPos center, int radius) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (level.getBlockState(cursor).getBlock() instanceof BedBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
