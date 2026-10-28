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

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.content.agents.build.Blueprint;
import com.siaws.siawsmod.content.agents.build.BlueprintLibrary;
import com.siaws.siawsmod.content.agents.build.BuildCard;
import com.siaws.siawsmod.content.agents.build.BuildCards;
import com.siaws.siawsmod.content.agents.build.BuildQueue;
import com.siaws.siawsmod.content.agents.build.BuildQueue.BuildJob;
import com.siaws.siawsmod.content.agents.knowledge.AgentKnowledge;
import com.siaws.siawsmod.content.agents.pool.AgentDecisionPool;
import com.siaws.siawsmod.content.agents.primitives.BreakSupport;
import com.siaws.siawsmod.content.agents.pool.PoolEntry;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.ComfortDetectors;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;
import com.siaws.siawsmod.content.llm.LlmActions;
import com.siaws.siawsmod.content.llm.LlmActions.LlmAction;
import com.siaws.siawsmod.content.llm.LlmClient;
import com.siaws.siawsmod.content.llm.LlmPrompts;
import com.siaws.siawsmod.content.mcp.McpSidecar;

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
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The agent decision/execution loop (design pillars 3+4): idle agents pick ONE action —
 * through the MCP sidecar when {@code agents.mcp.enabled} (takes precedence, needs nothing
 * from llm.*), else an LLM-chosen action when {@code llm.enabled}, else the built-in template
 * fallback — then claim a task-board task and execute it with vanilla-style steering. Java
 * executes; the model (if any) only decides, through either the MCP tool surface or the
 * validated {@link LlmActions} contract.
 *
 * <p>Executors (v1): COMFORT_LIGHT walks to the recomputed nearest dark spot and places one
 * torch per cycle from its own inventory; COMFORT_SHELTER walks to the deficit player and
 * raises a 3×3 opaque roof over their column plus one bed nearby (places-only, materials
 * from inventory or nearby chests); BUILD walks to the anchor, withdraws palette items
 * from chests near the anchor (every stocked chest counts until the palette is satisfied),
 * and places blocks. Places only — agents never break player-placed blocks in v1.
 *
 * <p>Pool citation: the board's resolution hooks already write outcome entries; on top, when
 * a task this agent claimed resolves DONE, the loop cites the pool entries it retrieved at
 * claim time via {@code pool.recordSuccess} (a lease-swept or failed task cites nothing).
 *
 * <p>Guardrails: every placement must be within {@code agents.guardRadius} of the task anchor
 * and on the task's block whitelist (torch / blueprint palette); violations are skipped with
 * a WARN. Movement is real navigation ({@code moveTo} + {@code isDone}), no teleports; a
 * stuck agent (no progress toward its goal for {@value #STUCK_CYCLES} cycles — circling
 * counts as stuck, motion is not progress) releases its claim, and repeated unroutable
 * releases terminal-fail the task (see {@link TaskBoard#UNREACHABLE_RELEASE_THRESHOLD}).
 * Suffocating blocks are never placed into the agent's own cell, and a suffocating agent
 * bails out of its task. Builds work from a staging cell — the nearest standable cell to
 * the anchor outside the remaining footprint — so an agent never entombs itself in the
 * only air pocket of its own build and an anchor on unstandable ground no longer strands
 * the walk. A build that runs short of materials is not failed: it is parked in the
 * knowledge ledger ({@code block:mazons:…} → blocked, unclaimable) until the station
 * chests near the anchor hold the remaining quantity (see
 * {@link com.siaws.siawsmod.content.agents.knowledge.AgentKnowledge}).
 */
public final class AgentDecisionLoop {
    /** After this many cycles without progress toward its goal, a claimed agent gives up (releases). */
    private static final int STUCK_CYCLES = 40;
    /** Executor releases suppress re-claims of the same task key for this long (game ticks). */
    private static final int RELEASE_COOLDOWN_TICKS = 20 * 60 * 5;
    /** Execution proceeds when the agent is within this distance of its target. */
    private static final double ARRIVAL_DISTANCE = 2.0;

    private static final AABB WHOLE_WORLD = new AABB(
            -30_000_000, -4096, -30_000_000, 30_000_000, 4096, 30_000_000);

    private static int ticksUntilRun;
    private static int ticksUntilLedgerRecheck;
    private static final Set<UUID> inFlightRequests = new HashSet<>();
    private static final Map<UUID, List<String>> pendingCitations = new HashMap<>();
    private static final Map<UUID, ProgressWatchdog> progressWatches = new HashMap<>();
    private static final Map<UUID, Map<String, Long>> releaseCooldowns = new HashMap<>();
    /** Per (taskId + placement index) consecutive failed/obstructed placement attempts. */
    private static final Map<String, Integer> placeFailures = new HashMap<>();

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
        boolean ledgerCycle = --ticksUntilLedgerRecheck <= 0;
        if (ledgerCycle) {
            ticksUntilLedgerRecheck = Config.AGENTS_KNOWLEDGE_RECHECK_SECONDS.get() * 20;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            runExecutionPass(level);
            if (ledgerCycle) {
                AgentWorldData.knowledge(level).recheckBlocked(level, AgentWorldData.board(level),
                        level.getGameTime());
            }
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
            if (claimed.isEmpty() && !inFlightRequests.contains(agent.getUUID())) {
                AgentActivity.idle(agent); // 1-tick label gap after a release is fine
            }
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
        List<BoardTask> open = new ArrayList<>(openTasks(level, board).stream()
                .filter(task -> claimableKeyFilter.test(task.key()))
                .toList());
        // A task this agent released not long ago (unroutable anchor, nothing to work with)
        // must not be instantly re-claimed: the live S1 run showed the model re-picking the
        // same unroutable anchor every decision cycle and burning a model call per lap.
        Map<String, Long> cooldowns = releaseCooldowns.get(agent.getUUID());
        if (cooldowns != null) {
            cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
            if (!cooldowns.isEmpty()) {
                open.removeIf(task -> cooldowns.containsKey(task.key()));
            }
        }
        if (open.isEmpty()) {
            return;
        }
        List<PoolEntry> retrieved = pool.retrieve(tagsOf(open), Config.AGENTS_POOL_TOP_K.get(),
                Config.AGENTS_POOL_TRUST_THRESHOLD.get(), now);
        rememberCitations(agent, retrieved);

        // MCP sidecar path takes precedence over the direct LLM path when enabled and alive:
        // the sidecar hosts the model session and executes the decision through McpTools.
        // Routed BEFORE the llm.enabled gate on purpose: agents.mcp.enabled alone must
        // activate the MCP path; on sidecar death the loop falls through to the routes below.
        boolean mcpEnabled = Config.AGENTS_MCP_ENABLED.get();
        McpSidecar sidecar = mcpEnabled ? McpSidecar.current() : null;
        if (mcpEnabled && sidecar == null) {
            // flag on but sidecar dead → fall through with a one-line warn
            SiasWorkshop.LOGGER.warn("[AgentDecisionLoop] agents.mcp.enabled but sidecar unavailable; falling back");
        }
        DecisionRoute decisionRoute = route(mcpEnabled, sidecar != null, Config.LLM_ENABLED.get());
        if (decisionRoute == DecisionRoute.MCP) {
            if (!inFlightRequests.add(agent.getUUID())) {
                return; // one request in flight per agent
            }
            AgentActivity.deciding(agent);
            AgentActivity.dispatched(level, agent, open.size());
            JsonObject mcpArgs = new JsonObject();
            mcpArgs.addProperty("agent", agent.getUUID().toString());
            sidecar.callTool("decide", mcpArgs, Config.AGENTS_MCP_DECISION_TIMEOUT_SECONDS.get())
                    .thenAccept(result -> level.getServer().execute(
                            () -> handleMcpResult(level, agent, board, open, leaseTicks, result)));
            return;
        }
        if (decisionRoute == DecisionRoute.TEMPLATE) {
            claimByPriority(level, board, agent, open, now, leaseTicks);
            return;
        }
        if (!inFlightRequests.add(agent.getUUID())) {
            return; // one request in flight per agent
        }
        AgentActivity.deciding(agent);
        AgentActivity.dispatched(level, agent, open.size());
        String observation = AgentObservation.build(agent, Config.AGENTS_OBSERVATION_BUDGET_TOKENS.get());
        List<BuildCard> cards = cardsFor(open);
        String user = LlmPrompts.userPrompt(observation, open,
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

    /** Where one idle agent's decision goes; see {@link #route}. */
    public enum DecisionRoute {
        MCP, DIRECT_LLM, TEMPLATE
    }

    /**
     * Pure gate order for {@link #decide}: MCP whenever the flag is on and the sidecar
     * singleton is alive (the flag alone suffices — no llm.* involvement), else the direct
     * LLM path when {@code llm.enabled}, else the template fallback. Public so gametests pin
     * the precedence without toggling ModConfigSpec values (those bake at JVM start).
     */
    public static DecisionRoute route(boolean mcpEnabled, boolean sidecarAvailable, boolean llmEnabled) {
        if (mcpEnabled && sidecarAvailable) {
            return DecisionRoute.MCP;
        }
        return llmEnabled ? DecisionRoute.DIRECT_LLM : DecisionRoute.TEMPLATE;
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
            AgentActivity.fallback(level, agent, String.valueOf(result.failure()));
            claimByPriority(level, board, agent, open, now, leaseTicks);
            return;
        }
        SiasWorkshop.LOGGER.info("[AgentDecisionLoop] agent {} decided via LLM: {} {}",
                agent.getUUID(), action.action(), action.args());
        AgentActivity.decision(level, agent, action.action(), action.args().toString());
        applyDecisionAction(level, board, agent, action, open, now, leaseTicks);
    }

    /** Clears the loop's static bookkeeping on server stop (covers world restart in one JVM). */
    public static void onServerStopping(ServerStoppingEvent event) {
        inFlightRequests.clear();
        pendingCitations.clear();
        progressWatches.clear();
        releaseCooldowns.clear();
        placeFailures.clear();
        ticksUntilRun = 0;
        ticksUntilLedgerRecheck = 0;
    }

    /**
     * MCP decision completion, always on the server thread. The sidecar has already executed
     * its decision through {@code McpTools} (claim/rest/say); this reconciles: a reported claim
     * the board doesn't reflect (stale key, race) and an unusable result both fall back to the
     * template priority claim, exactly like an unusable direct-LLM reply.
     */
    public static void handleMcpResult(ServerLevel level, WorkshopAgentEntity agent, TaskBoard board,
            List<BoardTask> open, int leaseTicks, McpSidecar.CallResult result) {
        inFlightRequests.remove(agent.getUUID());
        if (agent.isRemoved()) {
            return;
        }
        long now = level.getGameTime();
        if (!result.usable()) {
            SiasWorkshop.LOGGER.warn("[AgentDecisionLoop] agent {} MCP decision unusable ({}), falling back to templates",
                    agent.getUUID(), result.failure());
            AgentActivity.fallback(level, agent, String.valueOf(result.failure()));
            claimByPriority(level, board, agent, open, now, leaseTicks);
            return;
        }
        JsonObject payload = mcpPayload(result.json());
        if (payload.has("reject")) {
            // the sidecar's tool execution was rejected (claim raced, bad args, hidden task);
            // the reason used to be swallowed ("unknown" decision), costing debug rounds
            SiasWorkshop.LOGGER.warn("[AgentDecisionLoop] agent {} MCP decision rejected ({}), falling back to templates",
                    agent.getUUID(), payload.get("reject").getAsString());
            AgentActivity.fallback(level, agent, "mcp_reject");
            claimByPriority(level, board, agent, open, now, leaseTicks);
            return;
        }
        String decision = payload.has("decision") ? payload.get("decision").getAsString() : "unknown";
        String detail = payload.has("detail") ? payload.get("detail").getAsString() : "";
        if ("error".equals(decision)) {
            // model/sidecar error (bad args, model failure) — collapse like an unusable
            // direct-LLM reply instead of leaving the agent idle for a cycle
            SiasWorkshop.LOGGER.warn("[AgentDecisionLoop] agent {} MCP decision errored ({}), falling back to templates",
                    agent.getUUID(), detail);
            AgentActivity.fallback(level, agent, "mcp_error");
            claimByPriority(level, board, agent, open, now, leaseTicks);
            return;
        }
        SiasWorkshop.LOGGER.info("[AgentDecisionLoop] agent {} decided via MCP sidecar: {} {}",
                agent.getUUID(), decision, detail);
        AgentActivity.decision(level, agent, "mcp:" + decision, detail);
        if ("claim".equals(decision) && board.claimedBy(agent.getUUID()).isEmpty()) {
            claimByPriority(level, board, agent, open, now, leaseTicks);
        }
    }

    /** Extract the JSON the sidecar put in tools/call content[0].text; {} when absent. */
    private static JsonObject mcpPayload(JsonObject callResult) {
        try {
            if (callResult.has("content") && callResult.get("content").isJsonArray()
                    && !callResult.getAsJsonArray("content").isEmpty()) {
                String text = callResult.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(text);
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new JsonObject();
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

    private static void applyDecisionAction(ServerLevel level, TaskBoard board, WorkshopAgentEntity agent,
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
                    claim(level, board, agent, target, now, leaseTicks);
                }
            }
            case "rest" -> {
                agent.setCurrentTaskLabel("rest");
                AgentActivity.resting(agent); // deliberate idle
            }
            case "say" -> {
                String player = action.args().has("player") ? action.args().get("player").getAsString() : "";
                String msg = action.args().has("msg") ? action.args().get("msg").getAsString() : "";
                AgentActivity.say(level, agent, player, msg);
                agent.setCurrentTaskLabel("");
            }
            default -> claimByPriority(level, board, agent, open, now, leaseTicks); // valid but not decision-shaped
        }
    }

    private static void claimByPriority(ServerLevel level, TaskBoard board, WorkshopAgentEntity agent,
            List<BoardTask> open, long now, int leaseTicks) {
        BoardTask target = highestPriority(open);
        if (target != null) {
            claim(level, board, agent, target, now, leaseTicks);
        }
    }

    /** Fallback priority: both comfort types first (a player's need outranks construction), then builds. Public: shared by McpTools. */
    public static BoardTask highestPriority(List<BoardTask> open) {
        return open.stream()
                .min(Comparator.comparingInt(task -> task.type() == BoardTask.Type.BUILD ? 1 : 0))
                .orElse(null);
    }

    private static void claim(ServerLevel level, TaskBoard board, WorkshopAgentEntity agent,
            BoardTask task, long now, int leaseTicks) {
        if (board.claim(task.id(), agent.getUUID(), leaseTicks, now)) {
            SiasWorkshop.LOGGER.info("[AgentDecisionLoop] agent {} claimed {} ({})",
                    agent.getUUID(), task.key(), task.type());
            agent.setCurrentTaskLabel(task.key());
            AgentActivity.claimed(level, agent, task);
            clearStuck(agent);
        }
    }

    /**
     * Executor-side release with a re-claim cooldown ({@link #RELEASE_COOLDOWN_TICKS}): a task
     * the agent could make no progress on (unroutable anchor, no materials to hand) must not be
     * instantly re-claimed next decision cycle — the live S1 run showed the model re-picking
     * the same unroutable anchor every cycle and burning a model call per lap. Cooldowns are
     * per-agent and checked lazily in {@link #decide}.
     *
     * <p>{@code failureClass} feeds the board's consecutive-release streak: {@code "unroutable"}
     * from the stuck watchdog (the world makes the task impossible as it stands); null for
     * transient releases (restockable / world may change). When the board terminal-fails the
     * task ({@code FAILED("unreachable")} after {@link TaskBoard#UNREACHABLE_RELEASE_THRESHOLD}
     * unroutable releases), the anti-pattern is recorded in the pool, mirroring
     * {@code missing_material} terminal semantics.
     */
    private static void releaseForAgent(WorkshopAgentEntity agent, TaskBoard board, BoardTask task, long now,
            @Nullable String failureClass) {
        board.release(task.id(), agent.getUUID(), failureClass, now);
        agent.setCurrentTaskLabel("");
        releaseCooldowns.computeIfAbsent(agent.getUUID(), k -> new HashMap<>())
                .put(task.key(), now + RELEASE_COOLDOWN_TICKS);
        BoardTask after = board.tasks().stream().filter(t -> t.id().equals(task.id())).findFirst().orElse(null);
        if (after != null && after.status() == BoardTask.Status.FAILED) {
            AgentDecisionPool pool = AgentWorldData.pool((ServerLevel) agent.level());
            pool.recordOutcome(after.poolTags(), "failed: " + after.key() + " (" + after.detail() + ")",
                    after.poolPlan(), PoolEntry.Outcome.FAILED, after.failureReason(),
                    now - after.createdAtGameTime(), now, Config.AGENTS_POOL_MAX_ENTRIES.get());
        }
    }

    /**
     * True while {@code key} is in this agent's release cooldown (see {@link #releaseForAgent}).
     * Public: shared by the MCP tool surface so a cooled-down task is neither listed nor
     * claimable for the agent that just released it — otherwise the model burns a decision
     * lap re-picking it every cycle.
     */
    public static boolean inReleaseCooldown(UUID agentUuid, String key, long now) {
        Map<String, Long> cooldowns = releaseCooldowns.get(agentUuid);
        if (cooldowns == null) {
            return false;
        }
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        Long until = cooldowns.get(key);
        return until != null && until > now;
    }

    /**
     * The board tasks an agent may see at a decision point: OPEN tasks, excluding comfort
     * deficits whose player left (both comfort types are player-scoped). Public: shared by
     * the MCP tool surface ({@code McpTools}).
     */
    public static List<BoardTask> openTasks(ServerLevel level, TaskBoard board) {
        List<BoardTask> open = new ArrayList<>();
        AgentKnowledge knowledge = AgentWorldData.knowledge(level);
        for (BoardTask task : board.tasks()) {
            if (task.status() != BoardTask.Status.OPEN) {
                continue;
            }
            // parked in the knowledge ledger (materials short) — unclaimable until restocked
            if (knowledge.isBlocked(task.id())) {
                continue;
            }
            // comfort deficits belong to a specific player — never claim one whose player is gone
            if (task.type() != BoardTask.Type.BUILD && !playerOnline(level, task.key())) {
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
        if (isSuffocating(level, agent)) {
            // Head inside a suffocating block — entombed (the live 2026-10-20 leaves episode
            // killed an agent mid-"placing"). v1 agents never break blocks, so bail out and let
            // the world/user recover; the placementEntombsAgent guard prevents recurrence.
            SiasWorkshop.LOGGER.warn("[AgentDecisionLoop] agent {} suffocating at {}; bailing out of {}",
                    agent.getUUID(), agent.blockPosition(), task.key());
            releaseForAgent(agent, board, task, now, null);
            return false;
        }
        return switch (task.type()) {
            case COMFORT_LIGHT -> executeLight(level, board, agent, task);
            case COMFORT_SHELTER -> executeShelter(level, board, agent, task, now);
            case BUILD -> executeBuild(level, board, pool, agent, task, now, poolMaxEntries);
        };
    }

    /** True while the agent's eye sits in a suffocating block (the lethal half of entombment). */
    private static boolean isSuffocating(ServerLevel level, WorkshopAgentEntity agent) {
        BlockState eye = level.getBlockState(BlockPos.containing(agent.getEyePosition()));
        return eye.isSuffocating(level, BlockPos.containing(agent.getEyePosition()));
    }

    private static boolean executeLight(ServerLevel level, TaskBoard board, WorkshopAgentEntity agent, BoardTask task) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerUuidOf(task.key()));
        if (player == null) {
            releaseForAgent(agent, board, task, level.getGameTime(), null);
            return false;
        }
        BlockPos dark = nearestDarkSpot(level, player.blockPosition(), Config.AGENTS_LIGHT_SCAN_RADIUS.get(),
                Config.AGENTS_LIGHT_THRESHOLD.get(), agent.blockPosition());
        if (dark == null) {
            return true; // deficit resolving; the detector will auto-complete the task
        }
        if (!isCloseEnough(agent, dark)) {
            AgentActivity.working(agent, task, "heading there");
            navigate(agent, dark);
            if (isStuck(agent)) {
                releaseForAgent(agent, board, task, level.getGameTime(), "unroutable");
                clearStuck(agent);
                return false;
            }
            return true; // still walking
        }
        if (!hasItem(agent, Items.TORCH)) {
            // nothing attempted — release, don't fail
            releaseForAgent(agent, board, task, level.getGameTime(), null);
            return false;
        }
        BlockPos target = findTorchTarget(level, dark, player.blockPosition());
        if (target == null) {
            // No valid placement near the nearest spot (sealed cavity, unreachable edge):
            // holding the claim would deadlock the task forever, so release and let the
            // deficit re-raise — the world may change, or another agent may fare better.
            releaseForAgent(agent, board, task, level.getGameTime(), null);
            return false;
        }
        AgentActivity.working(agent, task, "placing");
        if (!placementEntombsAgent(level, Blocks.TORCH.defaultBlockState(), agent, target)
                && level.setBlock(target, Blocks.TORCH.defaultBlockState(), 3)) {
            consumeItem(agent, Items.TORCH);
        }
        return true;
    }

    // --- shelter executor ----------------------------------------------------------

    /** Neighbor cells the shelter executor tries for the bed, in preference order. */
    private static final List<BlockPos> BED_CANDIDATES = List.of(
            new BlockPos(2, 0, 0), new BlockPos(-2, 0, 0), new BlockPos(0, 0, 2), new BlockPos(0, 0, -2));

    /**
     * Shelter arrival is looser than the build/light 2-block radius: the anchor is the
     * PLAYER's own cell, which pathing cannot end inside, so the walk terminates one
     * cell short. Placements are range-checked against the guard radius, not proximity,
     * so a 3.5-block arrival changes no placement semantics.
     */
    private static final double SHELTER_ARRIVAL_DISTANCE = 3.5;

    /**
     * COMFORT_SHELTER executor (v1): the detector raises "no bed" / "exposed at night"
     * deficits; the fix is one bed on a free supported floor cell beside the player (clears
     * "no bed") plus a 3×3 opaque roof two blocks above them (blocks their sky column,
     * clearing "exposed"). The 2-block gap keeps the roof above standing heads, so the
     * placement never intersects a body. Places-only, materials from inventory or chests
     * near the player; the detector stays the completion oracle — when the fix resolves
     * the deficit it auto-completes the claim.
     */
    private static boolean executeShelter(ServerLevel level, TaskBoard board, WorkshopAgentEntity agent,
            BoardTask task, long now) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerUuidOf(task.key()));
        if (player == null) {
            releaseForAgent(agent, board, task, now, null);
            return false;
        }
        BlockPos anchor = player.blockPosition();
        if (agent.blockPosition().distSqr(anchor) > SHELTER_ARRIVAL_DISTANCE * SHELTER_ARRIVAL_DISTANCE) {
            AgentActivity.working(agent, task, "heading there");
            navigate(agent, anchor);
            if (isStuck(agent)) {
                releaseForAgent(agent, board, task, now, "unroutable");
                clearStuck(agent);
                return false;
            }
            return true; // still walking
        }

        // Remaining work, computed fresh each pass — the anchor, roof, and bed candidates
        // all follow the player's live position.
        List<BlockPos> roofRemaining = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos cell = anchor.offset(dx, 2, dz);
                if (!level.getBlockState(cell).is(Blocks.OAK_PLANKS)) {
                    roofRemaining.add(cell);
                }
            }
        }
        boolean bedPlaced = hasBedNearbyCell(level, anchor);
        if (!bedPlaced) {
            BlockPos bedCell = findBedCell(level, anchor);
            if (bedCell == null) {
                // no free supported floor cell beside the player — the world must change
                // (mirrors the light executor's no-valid-placement release)
                releaseForAgent(agent, board, task, now, null);
                return false;
            }
        }
        if (roofRemaining.isEmpty() && bedPlaced) {
            AgentActivity.working(agent, task, "shelter raised");
            if (ComfortDetectors.isSheltered(level, player, Config.AGENTS_SHELTER_RADIUS.get())) {
                return true; // deficit resolving; the detector auto-completes the claim
            }
            // the player left the canopy — hand the claim back; the detector re-raises
            // at their new position
            releaseForAgent(agent, board, task, now, null);
            return false;
        }

        AgentActivity.working(agent, task, "placing shelter");
        Map<String, Integer> needed = new HashMap<>();
        if (!bedPlaced) {
            needed.merge("minecraft:red_bed", 1, Integer::sum);
        }
        needed.merge("minecraft:oak_planks", roofRemaining.size(), Integer::sum);
        withdrawOnce(level, agent, anchor, needed);

        int toPlace = Config.AGENTS_PLACE_PER_CYCLE.get();
        int placed = 0;
        if (!bedPlaced && toPlace > 0) {
            BlockPos bedCell = findBedCell(level, anchor);
            if (bedCell != null && tryPlaceShelterCell(level, agent, player, bedCell, Blocks.RED_BED)) {
                placed++;
                toPlace--;
            }
        }
        for (int i = 0; i < roofRemaining.size() && toPlace > 0; i++) {
            if (tryPlaceShelterCell(level, agent, player, roofRemaining.get(i), Blocks.OAK_PLANKS)) {
                placed++;
                toPlace--;
            }
        }
        if (placed == 0) {
            boolean anyAir = !bedPlaced && findBedCell(level, anchor) != null;
            boolean anyAffordable = !bedPlaced && hasItem(agent, Items.RED_BED);
            for (BlockPos cell : roofRemaining) {
                if (level.getBlockState(cell).isAir()) {
                    anyAir = true;
                    if (hasItem(agent, Items.OAK_PLANKS)) {
                        anyAffordable = true;
                    }
                }
            }
            if (!anyAir || !anyAffordable) {
                // every remaining cell is obstructed, or nothing left to build with —
                // restockable or world-must-change, so hand the claim back rather than
                // holding it forever
                releaseForAgent(agent, board, task, now, null);
                return false;
            }
        }
        return true;
    }

    /** Attempts one shelter placement; {@code false} means skipped this pass (guarded, obstructed, unstocked, or occupied by a body). */
    private static boolean tryPlaceShelterCell(ServerLevel level, WorkshopAgentEntity agent, ServerPlayer player,
            BlockPos target, Block block) {
        if (!guardCheck(target, player.blockPosition(), BuiltInRegistries.BLOCK.getKey(block).toString())) {
            return false;
        }
        if (!level.getBlockState(target).isAir()) {
            return false; // obstructed — handled by the zero-progress release
        }
        if (placementIntersectsEntity(player, target) || placementEntombsAgent(level, block.defaultBlockState(), agent, target)) {
            return false; // a body is in the cell — retry a later pass when it clears
        }
        if (!hasItem(agent, block.asItem())) {
            return false;
        }
        if (level.setBlock(target, block.defaultBlockState(), 3)) {
            consumeItem(agent, block.asItem());
            return true;
        }
        return false;
    }

    /** First free, rigidly-supported neighbor cell for the bed, or null when boxed in.
     * Tries the player's own level and one below: mock/real players can rest a block
     * above the surface they stand on, and the bed belongs ON the surface. */
    @Nullable
    private static BlockPos findBedCell(ServerLevel level, BlockPos anchor) {
        for (int dy = 0; dy >= -1; dy--) {
            for (BlockPos candidate : BED_CANDIDATES) {
                BlockPos cell = anchor.offset(candidate.getX(), dy, candidate.getZ());
                if (level.getBlockState(cell).isAir() && Block.canSupportRigidBlock(level, cell.below())) {
                    return cell;
                }
            }
        }
        return null;
    }

    /** True when any bed candidate cell (same level or one below) already holds a bed. */
    private static boolean hasBedNearbyCell(ServerLevel level, BlockPos anchor) {
        for (int dy = 0; dy >= -1; dy--) {
            for (BlockPos candidate : BED_CANDIDATES) {
                if (level.getBlockState(anchor.offset(candidate.getX(), dy, candidate.getZ()))
                        .getBlock() instanceof BedBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when {@code pos}'s unit cell intersects the entity's bounding box — placing
     * anything into an occupied cell is refused, even for non-suffocating blocks. Public:
     * pinned by gametests.
     */
    public static boolean placementIntersectsEntity(net.minecraft.world.entity.Entity entity, BlockPos pos) {
        return entity.getBoundingBox().intersects(new AABB(pos));
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

        List<Blueprint.Placement> placements = blueprint.placementsSortedForBuild();
        int placed = countAlreadyPlaced(level, job.anchor(), placements);
        if (placed >= placements.size()) {
            board.complete(task.id(), agent.getUUID(), now, pool, poolMaxEntries);
            agent.setCurrentTaskLabel("");
            return false;
        }

        // Staging cell (live 2026-10-22 fixes): the agent works from the nearest standable
        // cell to the anchor OUTSIDE the remaining footprint. The old walk targeted the
        // anchor corner itself — it stranded builds on cliff tops (never within 2 blocks in
        // 3D) and parked the agent inside the footprint, where the entomb guard skipped the
        // agent's own cells every pass and the build could never complete.
        Set<BlockPos> remainingCells = new HashSet<>();
        for (int i = placed; i < placements.size(); i++) {
            remainingCells.add(job.anchor().offset(placements.get(i).rel()));
        }
        BlockPos staging = nearestStagingCell(level, job.anchor(), remainingCells, STAGING_SEARCH_RADIUS);
        if (staging == null) {
            // No standable ground near the anchor — the world makes the task impossible as
            // it stands. Same unroutable path as the stuck watchdog: three releases
            // terminal-fail the task instead of holding the claim forever.
            AgentActivity.working(agent, task, "no staging ground");
            releaseForAgent(agent, board, task, now, "unroutable");
            return false;
        }
        boolean boxedIn = false;
        for (BlockPos cell : remainingCells) {
            if (placementIntersectsEntity(agent, cell)) {
                boxedIn = true; // the agent's body sits inside the remaining footprint
                break;
            }
        }
        if (boxedIn || !isCloseEnough(agent, staging)) {
            // Self-extrication (live 2026-10-22): placement only runs with a body clear
            // of the blueprint, so every remaining cell — including the ones the agent
            // stood in — is placeable. An agent parked inside the footprint with a
            // staging cell one block away must still physically step out first.
            AgentActivity.working(agent, task, boxedIn ? "stepping out" : "heading there");
            navigate(agent, staging);
            if (isStuck(agent)) {
                releaseForAgent(agent, board, task, now, "unroutable");
                clearStuck(agent);
                return false;
            }
            return true; // still walking
        }

        // Materials: withdraw everything still needed, then park the task in the knowledge
        // ledger when short. Not a failure — blocked tasks sit unclaimable until the ledger
        // re-check sees the station chests restocked (or the remaining cells got placed).
        Map<String, Integer> needed = neededMaterials(placements, placed);
        withdrawOnce(level, agent, job.anchor(), needed);
        String shortItem = firstShortItem(agent, needed);
        if (shortItem != null) {
            blockForMaterials(level, board, agent, task, job, shortItem, placements, placed, now);
            return false;
        }

        int toPlace = Config.AGENTS_PLACE_PER_CYCLE.get();
        for (int i = placed; i < placements.size() && toPlace > 0; i++) {
            Blueprint.Placement placement = placements.get(i);
            BlockPos target = job.anchor().offset(placement.rel());
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(placement.blockId()));
            if (block == null || !guardCheck(target, job.anchor(), placement.blockId())) {
                continue;
            }
            BlockState current = level.getBlockState(target);
            String failureKey = task.id() + ":" + i;
            if (current.is(block)) {
                placeFailures.remove(failureKey); // resolved since last cycle
                continue;
            }
            if (!current.isAir()) {
                // Obstructed cell: try the break primitive first (the live 2026-10-20 leaves
                // episode — v1 used to retry until the 20-cycle give-up; breaking clears it
                // when tooling allows). Unbreakable-by-policy cells fall through to the
                // existing give-up below.
                if (Config.AGENTS_BREAKING_ENABLED.get() && BreakSupport.canBreak(level, agent, target)) {
                    AgentActivity.working(agent, task, "clearing obstruction");
                    BreakSupport.breakBlock(level, agent, target);
                    SiasWorkshop.LOGGER.info("[AgentDecisionLoop] agent {} cleared obstruction at {} for {}",
                            agent.getUUID(), target, task.key());
                    continue;
                }
                // Non-replaceable obstruction (natural leaves, a player-placed block): without a
                // give-up this cell would be retried forever — the live 2026-10-20 episode.
                // After PLACE_GIVE_UP_CYCLES consecutive cycles the task terminal-fails instead.
                if (recordPlaceFailure(board, task, failureKey, target, now, pool, poolMaxEntries)) {
                    agent.setCurrentTaskLabel("");
                    return false;
                }
                continue;
            }
            if (placementEntombsAgent(level, block.defaultBlockState(), agent, target)) {
                // The blueprint wants a suffocating block where the agent stands. The staging
                // cell is chosen outside the remaining footprint, so this only fires when the
                // agent drifted back into the footprint — skip this pass; the next pass walks
                // it back out to the staging cell before placing.
                SiasWorkshop.LOGGER.warn("[AgentDecisionLoop] skipping placement into agent's own cell: {}", target);
                continue;
            }
            if (!hasItem(agent, block.asItem())) {
                blockForMaterials(level, board, agent, task, job, placement.blockId(), placements, i, now);
                return false;
            }
            AgentActivity.working(agent, task, "placing");
            if (level.setBlock(target, block.defaultBlockState(), 3)) {
                placeFailures.remove(failureKey);
                consumeItem(agent, block.asItem());
                toPlace--;
            } else if (recordPlaceFailure(board, task, failureKey, target, now, pool, poolMaxEntries)) {
                agent.setCurrentTaskLabel("");
                return false;
            }
        }
        return true;
    }

    /** How far from the anchor the staging-cell search looks for standable ground. */
    private static final int STAGING_SEARCH_RADIUS = 6;

    /**
     * The cell the build executor works from: the air cell with rigid support below nearest
     * to {@code anchor} whose cell and head cell are not in {@code reservedCells} (the
     * remaining footprint — the agent never stages inside its own blueprint). Null when no
     * standable ground exists within {@code radius}. Public: pinned by gametests.
     */
    @Nullable
    public static BlockPos nearestStagingCell(ServerLevel level, BlockPos anchor, Set<BlockPos> reservedCells,
            int radius) {
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
                    if (reservedCells.contains(cursor) || reservedCells.contains(cursor.above())) {
                        continue;
                    }
                    if (!level.getBlockState(cursor).isAir()
                            || !Block.canSupportRigidBlock(level, cursor.below())) {
                        continue;
                    }
                    int dist = cursor.distManhattan(anchor);
                    if (dist < bestDist
                            || (dist == bestDist && best != null && cursor.getY() > best.getY())) {
                        best = new BlockPos(cursor);
                        bestDist = dist;
                    }
                }
            }
        }
        return best;
    }

    /** The first palette item the agent still needs but does not hold in sufficient count, or null. */
    @Nullable
    private static String firstShortItem(WorkshopAgentEntity agent, Map<String, Integer> needed) {
        for (Map.Entry<String, Integer> entry : needed.entrySet()) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.getKey()));
            if (item != Items.AIR && countItem(agent, item) < entry.getValue()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Parks a materials-short build in the knowledge ledger instead of failing it (live
     * 2026-10-22: the user restocks the station chests today; a future gather/mine/trade
     * surface resolves the same entries). Records the deficit, then releases the claim —
     * the blocked task is unclaimable until the ledger re-check resolves it.
     */
    private static void blockForMaterials(ServerLevel level, TaskBoard board, WorkshopAgentEntity agent,
            BoardTask task, BuildJob job, String itemId, List<Blueprint.Placement> placements,
            int fromIndex, long now) {
        int quantity = 0;
        for (int i = fromIndex; i < placements.size(); i++) {
            if (placements.get(i).blockId().equals(itemId)) {
                quantity++;
            }
        }
        AgentWorldData.knowledge(level).recordBlock(task, job, itemId, quantity, now);
        AgentActivity.working(agent, task, "materials short: " + itemId);
        releaseForAgent(agent, board, task, now, null);
    }

    /** Placement give-up: consecutive failed/obstructed cycles on one cell before the task terminal-fails. */
    private static final int PLACE_GIVE_UP_CYCLES = 20;

    /**
     * Counts one failed placement attempt on a cell; at {@link #PLACE_GIVE_UP_CYCLES} fails the
     * task {@code obstructed:<x>,<y>,<z>}. Returns true when the task was failed.
     */
    private static boolean recordPlaceFailure(TaskBoard board, BoardTask task, String failureKey, BlockPos target,
            long now, AgentDecisionPool pool, int poolMaxEntries) {
        int failures = placeFailures.merge(failureKey, 1, Integer::sum);
        if (failures < PLACE_GIVE_UP_CYCLES) {
            return false;
        }
        placeFailures.remove(failureKey);
        board.fail(task.id(), task.claimantUuid(),
                "obstructed:" + target.getX() + "," + target.getY() + "," + target.getZ(), now, pool, poolMaxEntries);
        return true;
    }

    /**
     * Self-preservation guard: true when placing {@code state} at {@code pos} would put a
     * suffocating block inside the agent's own bounding box (feet or head cell). Torches and
     * other non-suffocating blocks may share the agent's cell — standing on your own torch
     * cell is the normal light-executor case, not entombment. Public: pinned by gametests.
     */
    public static boolean placementEntombsAgent(ServerLevel level, BlockState state, WorkshopAgentEntity agent, BlockPos pos) {
        return state.isSuffocating(level, pos) && agent.getBoundingBox().intersects(new AABB(pos));
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

    /**
     * Withdraws palette items from chests near the anchor. Visits EVERY container in the
     * station-radius cube until the agent holds everything still needed — the first version
     * stopped at the first chest in scan order, so a stocked chest behind an unstocked one
     * was invisible and the task terminal-failed on missing_material (SEQ-026).
     */
    private static void withdrawOnce(ServerLevel level, WorkshopAgentEntity agent, BlockPos anchor,
            Map<String, Integer> needed) {
        if (needed.isEmpty()) {
            return;
        }
        int radius = Config.AGENTS_STATION_RADIUS.get();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius && !allSatisfied(agent, needed); dx++) {
            for (int dy = -radius; dy <= radius && !allSatisfied(agent, needed); dy++) {
                for (int dz = -radius; dz <= radius && !allSatisfied(agent, needed); dz++) {
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
                }
            }
        }
    }

    private static boolean allSatisfied(WorkshopAgentEntity agent, Map<String, Integer> needed) {
        for (Map.Entry<String, Integer> entry : needed.entrySet()) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.getKey()));
            if (item != Items.AIR && countItem(agent, item) < entry.getValue()) {
                return false;
            }
        }
        return true;
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
        trackStuck(agent, target);
    }

    private static void trackStuck(WorkshopAgentEntity agent, BlockPos target) {
        progressWatches.computeIfAbsent(agent.getUUID(), uuid -> new ProgressWatchdog())
                .record(target, agent.position());
    }

    private static boolean isStuck(WorkshopAgentEntity agent) {
        ProgressWatchdog watch = progressWatches.get(agent.getUUID());
        return watch != null && watch.cycles() >= STUCK_CYCLES;
    }

    private static void clearStuck(WorkshopAgentEntity agent) {
        progressWatches.remove(agent.getUUID());
    }

    /**
     * Progress-vs-goal stuck detection. The previous watchdog counted position-IDENTICAL
     * ticks, so an agent circling its target — constant motion, zero progress (arrival-circling
     * around an unreachable anchor) — never tripped it. This records the squared distance to
     * the CURRENT goal instead: strict improvement resets the streak; anything else (motionless
     * or moving without progress) increments it.
     *
     * <p>Distances are measured from DOUBLE positions, not block cells: a walking agent moves
     * ~0.05 blocks/tick, which only changes its block position every few ticks — an integer-
     * cell metric cannot distinguish slow walking from standing still and would trip mid-walk.
     * Pure and level-free so gametests can drive it with synthetic samples.
     */
    public static final class ProgressWatchdog {
        /** Minimum real improvement (blocks²) that resets the no-progress streak. */
        private static final double PROGRESS_EPSILON_SQ = 1.0e-4;

        private BlockPos target;
        private double bestDistSq = Double.MAX_VALUE;
        private int cycles;

        /**
         * Records one (target, position) sample; returns consecutive cycles without progress.
         * A changed target or any strictly closer sample resets the streak to 0.
         */
        public int record(BlockPos target, net.minecraft.world.phys.Vec3 position) {
            double distSq = position.distanceToSqr(
                    target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            if (this.target == null || !this.target.equals(target)
                    || distSq < bestDistSq - PROGRESS_EPSILON_SQ) {
                this.target = target.immutable();
                this.bestDistSq = distSq;
                this.cycles = 0;
            } else {
                this.cycles++;
            }
            return this.cycles;
        }

        public int cycles() {
            return cycles;
        }

        public void reset() {
            this.target = null;
            this.bestDistSq = Double.MAX_VALUE;
            this.cycles = 0;
        }
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
