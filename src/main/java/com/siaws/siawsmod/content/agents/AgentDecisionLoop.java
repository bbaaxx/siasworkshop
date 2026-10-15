package com.siaws.siawsmod.content.agents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.content.agents.build.Blueprint;
import com.siaws.siawsmod.content.agents.build.BlueprintLibrary;
import com.siaws.siawsmod.content.agents.build.BuildCard;
import com.siaws.siawsmod.content.agents.build.BuildCards;
import com.siaws.siawsmod.content.agents.build.BuildQueue;
import com.siaws.siawsmod.content.agents.build.BuildQueue.BuildJob;
import com.siaws.siawsmod.content.agents.pool.AgentDecisionPool;
import com.siaws.siawsmod.content.agents.pool.PoolEntry;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;
import com.siaws.siawsmod.content.llm.LlmActions;
import com.siaws.siawsmod.content.llm.LlmActions.LlmAction;
import com.siaws.siawsmod.content.llm.LlmClient;
import com.siaws.siawsmod.content.llm.LlmPrompts;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The agent decision/execution loop (design pillars 3+4): idle agents pick ONE action — an
 * LLM-chosen action when {@code llm.enabled}, otherwise the built-in template fallback — then
 * claim a task-board task and execute it with vanilla-style steering. Java executes; the LLM
 * (if any) only decides, and only inside the validated {@link LlmActions} contract.
 *
 * <p>Executors (v1): COMFORT_LIGHT walks to the recomputed nearest dark spot and places one
 * torch per cycle from its own inventory; BUILD walks to the anchor, withdraws palette items
 * from a nearby chest (one chest visit per cycle), and places blocks. COMFORT_SHELTER is
 * never claimed by the fallback (LLM or a later initiative). Places only — agents never
 * break player-placed blocks in v1.
 *
 * <p>Pool citation: the board's resolution hooks already write outcome entries; on top, when
 * a task this agent claimed resolves DONE, the loop cites the pool entries it retrieved at
 * claim time via {@code pool.recordSuccess} (a lease-swept or failed task cites nothing).
 *
 * <p>Guardrails: every placement must be within {@code agents.guardRadius} of the task anchor
 * and on the task's block whitelist (torch / blueprint palette); violations are skipped with
 * a WARN. Movement is real navigation ({@code moveTo} + {@code isDone}), no teleports; a
 * stuck agent releases its claim after {@value #STUCK_CYCLES} unmoving cycles.
 */
public final class AgentDecisionLoop {
    /** After this many cycles without meaningful movement, a claimed agent gives up (releases). */
    private static final int STUCK_CYCLES = 4;
    /** Execution proceeds when the agent is within this distance of its target. */
    private static final double ARRIVAL_DISTANCE = 2.0;

    private static final AABB WHOLE_WORLD = new AABB(
            -30_000_000, -4096, -30_000_000, 30_000_000, 4096, 30_000_000);

    private static int ticksUntilRun;
    private static final Set<UUID> inFlightRequests = new HashSet<>();
    private static final Map<UUID, List<String>> pendingCitations = new HashMap<>();
    private static final Map<UUID, int[]> lastPositions = new HashMap<>();
    private static final Map<UUID, Integer> stuckCycles = new HashMap<>();

    private AgentDecisionLoop() {}

    /**
     * Server tick hook, registered on the NeoForge event bus from the mod entrypoint.
     * Execution runs every tick (claimed agents steer, place, and renew leases); only the
     * decision pass for idle agents is interval-gated — a 30 s decision cadence must not
     * stall walking agents or let 200-tick leases lapse.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Config.AGENTS_EXECUTION_LOOP_ENABLED.get()) {
            return;
        }
        boolean decisionCycle = --ticksUntilRun <= 0;
        if (decisionCycle) {
            ticksUntilRun = Config.AGENTS_DECISION_INTERVAL_SECONDS.get() * 20;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            runExecutionPass(level);
            if (decisionCycle) {
                runDecisions(level, key -> true);
            }
        }
    }

    /** One full loop pass (execution + decisions) for one level; public so gametests can drive it directly. */
    public static void runOnce(ServerLevel level) {
        runOnce(level, key -> true);
    }

    /**
     * Full loop pass with a claimable-task filter. Production passes {@code key -> true};
     * gametests pass their own task key so a shared GameTest world cannot divert the agent
     * onto another test's open tasks.
     */
    public static void runOnce(ServerLevel level, Predicate<String> claimableKeyFilter) {
        runExecutionPass(level);
        runDecisions(level, claimableKeyFilter);
    }

    /** Per-tick pass: claimed agents execute their task and renew leases. */
    public static void runExecutionPass(ServerLevel level) {
        TaskBoard board = AgentWorldData.board(level);
        AgentDecisionPool pool = AgentWorldData.pool(level);
        long now = level.getGameTime();
        int leaseTicks = Config.AGENTS_TASK_LEASE_TICKS.get();
        int poolMaxEntries = Config.AGENTS_POOL_MAX_ENTRIES.get();

        for (WorkshopAgentEntity agent : level.getEntitiesOfClass(WorkshopAgentEntity.class, WHOLE_WORLD)) {
            resolveCitations(agent, board, pool, now);
            List<BoardTask> claimed = board.claimedBy(agent.getUUID());
            if (!claimed.isEmpty()) {
                BoardTask task = claimed.get(0);
                if (execute(level, board, pool, agent, task, now, poolMaxEntries)) {
                    board.renewLease(task.id(), agent.getUUID(), leaseTicks, now);
                }
            }
        }
    }

    /** Interval pass: agents with no claim observe, decide (LLM or template), and claim. */
    public static void runDecisions(ServerLevel level, Predicate<String> claimableKeyFilter) {
        TaskBoard board = AgentWorldData.board(level);
        AgentDecisionPool pool = AgentWorldData.pool(level);
        long now = level.getGameTime();
        int leaseTicks = Config.AGENTS_TASK_LEASE_TICKS.get();
        int poolMaxEntries = Config.AGENTS_POOL_MAX_ENTRIES.get();

        for (WorkshopAgentEntity agent : level.getEntitiesOfClass(WorkshopAgentEntity.class, WHOLE_WORLD)) {
            if (!board.claimedBy(agent.getUUID()).isEmpty()) {
                continue;
            }
            agent.setCurrentTaskLabel("");
            decide(level, board, pool, agent, now, leaseTicks, poolMaxEntries, claimableKeyFilter);
        }
    }

    // --- decision ----------------------------------------------------------------

    private static void decide(ServerLevel level, TaskBoard board, AgentDecisionPool pool, WorkshopAgentEntity agent,
            long now, int leaseTicks, int poolMaxEntries, Predicate<String> claimableKeyFilter) {
        List<BoardTask> open = openTasks(level, board).stream()
                .filter(task -> claimableKeyFilter.test(task.key()))
                .toList();
        if (open.isEmpty()) {
            return;
        }
        List<PoolEntry> retrieved = pool.retrieve(tagsOf(open), Config.AGENTS_POOL_TOP_K.get(),
                Config.AGENTS_POOL_TRUST_THRESHOLD.get(), now);
        rememberCitations(agent, retrieved);

        if (!Config.LLM_ENABLED.get()) {
            claimByPriority(board, agent, open, now, leaseTicks);
            return;
        }
        if (!inFlightRequests.add(agent.getUUID())) {
            return; // one request in flight per agent
        }
        String observation = AgentObservation.build(agent, Config.AGENTS_OBSERVATION_BUDGET_TOKENS.get());
        List<BuildCard> cards = cardsFor(open);
        String user = LlmPrompts.userPrompt(observation,
                retrieved.stream().map(LlmPrompts::poolSummaryLine).toList(), cards);
        try {
            LlmClient.request(level.getServer(), LlmPrompts.systemLine(), user,
                    result -> handleLlmResult(level, agent, board, open, leaseTicks, result));
        } catch (RuntimeException e) {
            // the request itself threw (e.g. dead executor): free the in-flight slot and
            // take the same fallback path an unusable answer would take
            inFlightRequests.remove(agent.getUUID());
            handleLlmResult(level, agent, board, open, leaseTicks,
                    LlmClient.LlmResult.failed("request_throw"));
        }
    }

    /**
     * LLM completion handler, always on the server thread. Game time is read FRESH here —
     * the reply may arrive after the decision-time lease (200 ticks) has lapsed, and claiming
     * with a stale {@code now} would claim a task born-expired.
     */
    private static void handleLlmResult(ServerLevel level, WorkshopAgentEntity agent, TaskBoard board,
            List<BoardTask> open, int leaseTicks, LlmClient.LlmResult result) {
        inFlightRequests.remove(agent.getUUID());
        if (agent.isRemoved()) {
            return;
        }
        long now = level.getGameTime();
        LlmAction action = result.usable() ? LlmActions.validate(result.json()) : null;
        if (action == null) {
            SiasWorkshop.LOGGER.warn("[AgentDecisionLoop] agent {} LLM reply unusable ({}), falling back to templates",
                    agent.getUUID(), result.failure());
            claimByPriority(board, agent, open, now, leaseTicks);
            return;
        }
        SiasWorkshop.LOGGER.info("[AgentDecisionLoop] agent {} decided via LLM: {} {}",
                agent.getUUID(), action.action(), action.args());
        applyDecisionAction(board, agent, action, open, now, leaseTicks);
    }

    /** Clears the loop's static bookkeeping on server stop (covers world restart in one JVM). */
    public static void onServerStopping(ServerStoppingEvent event) {
        inFlightRequests.clear();
        pendingCitations.clear();
        lastPositions.clear();
        stuckCycles.clear();
        ticksUntilRun = 0;
    }

    private static List<BuildCard> cardsFor(List<BoardTask> open) {
        BoardTask build = open.stream().filter(task -> task.type() == BoardTask.Type.BUILD).findFirst().orElse(null);
        if (build == null) {
            return List.of();
        }
        BuildJob job = BuildQueue.parseJob(build);
        Set<String> tokens = job != null ? BuildQueue.nameTokens(job.blueprintName()) : Set.of();
        return BuildCards.INSTANCE.retrieve(tokens, 3);
    }

    private static void applyDecisionAction(TaskBoard board, WorkshopAgentEntity agent,
            LlmAction action, List<BoardTask> open, long now, int leaseTicks) {
        switch (action.action()) {
            case "claim" -> {
                BoardTask target = null;
                if (action.args().has("task")) {
                    String key = action.args().get("task").getAsString();
                    target = open.stream().filter(task -> task.key().equals(key)).findFirst().orElse(null);
                }
                if (target == null) {
                    target = highestPriority(open);
                }
                if (target != null) {
                    claim(board, agent, target, now, leaseTicks);
                }
            }
            case "rest", "say" -> agent.setCurrentTaskLabel(action.action()); // deliberate idle
            default -> claimByPriority(board, agent, open, now, leaseTicks); // valid but not decision-shaped
        }
    }

    private static void claimByPriority(TaskBoard board, WorkshopAgentEntity agent, List<BoardTask> open,
            long now, int leaseTicks) {
        BoardTask target = highestPriority(open);
        if (target != null) {
            claim(board, agent, target, now, leaseTicks);
        }
    }

    /** Fallback priority: comfort first, then builds; shelter is never fallback-claimed. */
    private static BoardTask highestPriority(List<BoardTask> open) {
        return open.stream()
                .filter(task -> task.type() != BoardTask.Type.COMFORT_SHELTER)
                .min(Comparator.comparingInt(task -> task.type() == BoardTask.Type.COMFORT_LIGHT ? 0 : 1))
                .orElse(null);
    }

    private static void claim(TaskBoard board, WorkshopAgentEntity agent, BoardTask task, long now, int leaseTicks) {
        if (board.claim(task.id(), agent.getUUID(), leaseTicks, now)) {
            SiasWorkshop.LOGGER.info("[AgentDecisionLoop] agent {} claimed {} ({})",
                    agent.getUUID(), task.key(), task.type());
            agent.setCurrentTaskLabel(task.key());
            clearStuck(agent);
        }
    }

    private static List<BoardTask> openTasks(ServerLevel level, TaskBoard board) {
        List<BoardTask> open = new ArrayList<>();
        for (BoardTask task : board.tasks()) {
            if (task.status() != BoardTask.Status.OPEN) {
                continue;
            }
            // comfort deficits belong to a specific player — never claim one whose player is gone
            if ((task.type() == BoardTask.Type.COMFORT_LIGHT || task.type() == BoardTask.Type.COMFORT_SHELTER)
                    && !playerOnline(level, task.key())) {
                continue;
            }
            open.add(task);
        }
        return open;
    }

    private static boolean playerOnline(ServerLevel level, String comfortKey) {
        UUID id = playerUuidOf(comfortKey);
        for (ServerPlayer player : level.players()) {
            if (player.getUUID().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Tags of all open tasks (e.g. comfort, light, build) — the retrieval query for this decision. */
    private static Set<String> tagsOf(List<BoardTask> open) {
        Set<String> tags = new LinkedHashSet<>();
        for (BoardTask task : open) {
            tags.addAll(task.poolTags());
        }
        return tags;
    }

    // --- execution ----------------------------------------------------------------

    /** Returns true while the task is being worked (lease should be renewed). */
    private static boolean execute(ServerLevel level, TaskBoard board, AgentDecisionPool pool, WorkshopAgentEntity agent,
            BoardTask task, long now, int poolMaxEntries) {
        return switch (task.type()) {
            case COMFORT_LIGHT -> executeLight(level, board, agent, task);
            case BUILD -> executeBuild(level, board, pool, agent, task, now, poolMaxEntries);
            case COMFORT_SHELTER -> { // never claimed by fallback; release if we somehow hold one
                board.release(task.id(), agent.getUUID(), now);
                agent.setCurrentTaskLabel("");
                yield false;
            }
        };
    }

    private static boolean executeLight(ServerLevel level, TaskBoard board, WorkshopAgentEntity agent, BoardTask task) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerUuidOf(task.key()));
        if (player == null) {
            board.release(task.id(), agent.getUUID(), level.getGameTime());
            agent.setCurrentTaskLabel("");
            return false;
        }
        BlockPos dark = nearestDarkSpot(level, player.blockPosition(), Config.AGENTS_LIGHT_SCAN_RADIUS.get(),
                Config.AGENTS_LIGHT_THRESHOLD.get(), agent.blockPosition());
        if (dark == null) {
            return true; // deficit resolving; the detector will auto-complete the task
        }
        if (!isCloseEnough(agent, dark)) {
            navigate(agent, dark);
            if (isStuck(agent)) {
                board.release(task.id(), agent.getUUID(), level.getGameTime());
                agent.setCurrentTaskLabel("");
                clearStuck(agent);
                return false;
            }
            return true; // still walking
        }
        if (!hasItem(agent, Items.TORCH)) {
            // nothing attempted — release, don't fail
            board.release(task.id(), agent.getUUID(), level.getGameTime());
            agent.setCurrentTaskLabel("");
            return false;
        }
        BlockPos target = findTorchTarget(level, dark, player.blockPosition());
        if (target == null) {
            // No valid placement near the nearest spot (sealed cavity, unreachable edge):
            // holding the claim would deadlock the task forever, so release and let the
            // deficit re-raise — the world may change, or another agent may fare better.
            board.release(task.id(), agent.getUUID(), level.getGameTime());
            agent.setCurrentTaskLabel("");
            return false;
        }
        if (level.setBlock(target, Blocks.TORCH.defaultBlockState(), 3)) {
            consumeItem(agent, Items.TORCH);
        }
        return true;
    }

    private static boolean executeBuild(ServerLevel level, TaskBoard board, AgentDecisionPool pool,
            WorkshopAgentEntity agent, BoardTask task, long now, int poolMaxEntries) {
        BuildJob job = BuildQueue.parseJob(task);
        if (job == null) {
            board.fail(task.id(), agent.getUUID(), "malformed_job", now, pool, poolMaxEntries);
            agent.setCurrentTaskLabel("");
            return false;
        }
        Blueprint blueprint = BlueprintLibrary.INSTANCE.get(job.blueprintName());
        if (blueprint == null) {
            board.fail(task.id(), agent.getUUID(), "unknown_blueprint:" + job.blueprintName(), now, pool, poolMaxEntries);
            agent.setCurrentTaskLabel("");
            return false;
        }
        if (!isCloseEnough(agent, job.anchor())) {
            navigate(agent, job.anchor());
            if (isStuck(agent)) {
                board.release(task.id(), agent.getUUID(), now);
                agent.setCurrentTaskLabel("");
                clearStuck(agent);
                return false;
            }
            return true; // still walking
        }

        List<Blueprint.Placement> placements = blueprint.placementsSortedForBuild();
        int placed = countAlreadyPlaced(level, job.anchor(), placements);
        if (placed >= placements.size()) {
            board.complete(task.id(), agent.getUUID(), now, pool, poolMaxEntries);
            agent.setCurrentTaskLabel("");
            return false;
        }

        int toPlace = Config.AGENTS_PLACE_PER_CYCLE.get();
        int windowEnd = Math.min(placed + toPlace, placements.size());
        for (int i = placed; i < windowEnd; i++) {
            Blueprint.Placement upcoming = placements.get(i);
            Block upcomingBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(upcoming.blockId()));
            if (upcomingBlock != null && !hasItem(agent, upcomingBlock.asItem())) {
                withdrawOnce(level, agent, job.anchor(), neededMaterials(placements, placed));
                break;
            }
        }
        for (int i = placed; i < placements.size() && toPlace > 0; i++) {
            Blueprint.Placement placement = placements.get(i);
            BlockPos target = job.anchor().offset(placement.rel());
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(placement.blockId()));
            if (block == null || !guardCheck(target, job.anchor(), placement.blockId())) {
                continue;
            }
            BlockState current = level.getBlockState(target);
            if (!current.isAir()) {
                continue; // already handled by countAlreadyPlaced on later passes
            }
            if (!hasItem(agent, block.asItem())) {
                board.fail(task.id(), agent.getUUID(), "missing_material:" + placement.blockId(), now, pool, poolMaxEntries);
                agent.setCurrentTaskLabel("");
                return false;
            }
            if (level.setBlock(target, block.defaultBlockState(), 3)) {
                consumeItem(agent, block.asItem());
                toPlace--;
            }
        }
        return true;
    }

    /** Guardrail check shared by executors: inside the guard radius of the anchor (block whitelist is per-caller). */
    private static boolean guardCheck(BlockPos target, BlockPos anchor, String blockId) {
        double guard = Config.AGENTS_GUARD_RADIUS.get();
        if (target.distSqr(anchor) > guard * guard) {
            SiasWorkshop.LOGGER.warn(
                    "[AgentDecisionLoop] guardrail: {} outside guard radius of anchor {}", target, anchor);
            return false;
        }
        return blockId != null;
    }

    private static void withdrawOnce(ServerLevel level, WorkshopAgentEntity agent, BlockPos anchor,
            Map<String, Integer> needed) {
        if (needed.isEmpty()) {
            return;
        }
        int radius = Config.AGENTS_STATION_RADIUS.get();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
                    BlockEntity entity = level.getBlockEntity(cursor);
                    if (!(entity instanceof Container chest)) {
                        continue;
                    }
                    for (Map.Entry<String, Integer> entry : needed.entrySet()) {
                        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.getKey()));
                        if (item == Items.AIR) {
                            continue;
                        }
                        int have = countItem(agent, item);
                        int want = entry.getValue() - have;
                        if (want <= 0) {
                            continue;
                        }
                        int moved = 0;
                        for (int slot = 0; slot < chest.getContainerSize() && moved < want; slot++) {
                            ItemStack stack = chest.getItem(slot);
                            if (!stack.is(item)) {
                                continue;
                            }
                            ItemStack taken = chest.removeItem(slot, want - moved);
                            moved += taken.getCount();
                            agent.getInventory().addItem(taken);
                        }
                    }
                    return; // one chest visit per cycle
                }
            }
        }
    }

    private static Map<String, Integer> neededMaterials(List<Blueprint.Placement> placements, int fromIndex) {
        Map<String, Integer> needed = new HashMap<>();
        for (int i = fromIndex; i < placements.size(); i++) {
            needed.merge(placements.get(i).blockId(), 1, Integer::sum);
        }
        return needed;
    }

    private static int countAlreadyPlaced(ServerLevel level, BlockPos anchor, List<Blueprint.Placement> placements) {
        int placed = 0;
        for (Blueprint.Placement placement : placements) {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(placement.blockId()));
            if (block != null && level.getBlockState(anchor.offset(placement.rel())).is(block)) {
                placed++;
            }
        }
        return placed;
    }

    // --- movement ----------------------------------------------------------------

    private static boolean isCloseEnough(WorkshopAgentEntity agent, BlockPos target) {
        return agent.blockPosition().distSqr(target) <= ARRIVAL_DISTANCE * ARRIVAL_DISTANCE;
    }

    private static void navigate(WorkshopAgentEntity agent, BlockPos target) {
        PathNavigation nav = agent.getNavigation();
        // Re-path only when idle or once per second — re-creating the path every tick
        // starves navigation and, on pathfinding failure (isDone with no path), used to let
        // callers believe the agent had arrived.
        if (nav.isDone() || agent.tickCount % 10 == 0) {
            nav.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        }
        trackStuck(agent);
    }

    private static void trackStuck(WorkshopAgentEntity agent) {
        int[] last = lastPositions.get(agent.getUUID());
        BlockPos pos = agent.blockPosition();
        if (last != null && last[0] == pos.getX() && last[1] == pos.getY() && last[2] == pos.getZ()) {
            stuckCycles.merge(agent.getUUID(), 1, Integer::sum);
        } else {
            stuckCycles.put(agent.getUUID(), 0);
        }
        lastPositions.put(agent.getUUID(), new int[] {pos.getX(), pos.getY(), pos.getZ()});
    }

    private static boolean isStuck(WorkshopAgentEntity agent) {
        return stuckCycles.getOrDefault(agent.getUUID(), 0) >= STUCK_CYCLES;
    }

    private static void clearStuck(WorkshopAgentEntity agent) {
        stuckCycles.remove(agent.getUUID());
        lastPositions.remove(agent.getUUID());
    }

    // --- pool citations ------------------------------------------------------------

    /** Replaces the agent's citation candidates with the entries retrieved for this decision. */
    private static void rememberCitations(WorkshopAgentEntity agent, List<PoolEntry> retrieved) {
        if (!retrieved.isEmpty()) {
            pendingCitations.put(agent.getUUID(), retrieved.stream().map(PoolEntry::id).toList());
        }
    }

    /**
     * When a task this agent claimed resolves, its citation candidates are consumed: DONE
     * cites the retrieved entries via {@code pool.recordSuccess} (plan reuse earned the
     * success); FAILED credits nothing, and the stale retrievals are dropped so they cannot
     * ride into the agent's next task. Lease sweeps clear the claim without ever reaching
     * here. The board's own resolution hook writes the outcome entry; this only bumps the
     * reused entries' success counters.
     */
    private static void resolveCitations(WorkshopAgentEntity agent, TaskBoard board, AgentDecisionPool pool, long now) {
        List<String> entryIds = pendingCitations.get(agent.getUUID());
        if (entryIds == null || entryIds.isEmpty()) {
            return;
        }
        for (BoardTask task : board.tasks()) {
            boolean mine = agent.getUUID().equals(task.claimantUuid());
            if (mine && (task.status() == BoardTask.Status.DONE || task.status() == BoardTask.Status.FAILED)) {
                if (task.status() == BoardTask.Status.DONE) {
                    for (String entryId : entryIds) {
                        pool.recordSuccess(entryId, now);
                    }
                }
                // FAILED credits nothing, and stale retrievals must not ride into the next task
                pendingCitations.remove(agent.getUUID());
                return;
            }
        }
    }

    // --- world helpers (shared shapes) ----------------------------------------------

    /** The dark spot nearest to {@code from} around {@code center}, using the detector's criteria. */
    private static BlockPos nearestDarkSpot(ServerLevel level, BlockPos center, int radius, int threshold, BlockPos from) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
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
                        double dSq = from.distSqr(cursor);
                        if (dSq < bestSq) {
                            bestSq = dSq;
                            best = cursor.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /** A guard-checked torch position near the dark spot: rigid floor, inside guard radius of the player. */
    private static BlockPos findTorchTarget(ServerLevel level, BlockPos dark, BlockPos playerPos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    cursor.set(dark.getX() + dx, dark.getY() + dy, dark.getZ() + dz);
                    if (!level.getBlockState(cursor).isAir()) {
                        continue;
                    }
                    if (!Block.canSupportRigidBlock(level, cursor.move(Direction.DOWN))) {
                        continue;
                    }
                    cursor.move(Direction.UP);
                    if (guardCheck(cursor, playerPos, "torch")) {
                        return cursor.immutable();
                    }
                }
            }
        }
        return null;
    }

    // --- inventory helpers ------------------------------------------------------------

    private static boolean hasItem(WorkshopAgentEntity agent, Item item) {
        return countItem(agent, item) > 0;
    }

    private static int countItem(WorkshopAgentEntity agent, Item item) {
        return ContainerHelper.clearOrCountMatchingItems(agent.getInventory(), stack -> stack.is(item), -1, true);
    }

    private static void consumeItem(WorkshopAgentEntity agent, Item item) {
        ContainerHelper.clearOrCountMatchingItems(agent.getInventory(), stack -> stack.is(item), 1, false);
    }

    /** Extracts the player UUID suffix of a {@code comfort:<kind>:<uuid>} task key. */
    private static UUID playerUuidOf(String key) {
        try {
            return UUID.fromString(key.substring(key.lastIndexOf(':') + 1));
        } catch (RuntimeException e) {
            return new UUID(0L, 0L);
        }
    }
}
