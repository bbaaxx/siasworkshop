package com.siaws.siawsmod.gametest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.content.agents.AgentDecisionLoop;
import com.siaws.siawsmod.content.agents.AgentWorldData;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;
import com.siaws.siawsmod.content.mcp.McpSidecar;
import com.siaws.siawsmod.content.mcp.McpTools;
import com.siaws.siawsmod.init.SiasWorkshopEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Game tests for the S1 MCP sidecar path ({@link McpSidecar}/{@link McpTools}): the mod-side
 * JSON-RPC transport against an in-JVM fake sidecar over pipes (handshake, catalog serving,
 * a full decide cycle that observes and claims through the real board, structured rejects for
 * malformed calls, and template fallback when the sidecar dies). The config flag itself stays
 * default-OFF — a ModConfigSpec bakes at JVM start, so flag gating is verified by inspection
 * and live runs, not by toggling mid-test. The fake peer plays the sidecar's CLIENT role
 * exactly like sidecar.py: it initiates initialize, then serves {@code decide}.
 */
@GameTestHolder("siasworkshop")
public final class AgentsMcpGameTest {
    private AgentsMcpGameTest() {}

    @GameTest(template = "empty")
    public static void mcpHandshakeCatalogServes(GameTestHelper helper) throws Exception {
        FakeSidecarPeer peer = new FakeSidecarPeer(FakeSidecarPeer.MODE_CATALOG_PROBE, null);
        McpSidecar sidecar = startSidecar(helper, peer);

        helper.assertTrue(peer.catalog.await(10, TimeUnit.SECONDS), "peer never got the tools/list response");
        List<String> names = peer.catalogNames;
        helper.assertTrue(names.contains("observe_self") && names.contains("observe_board")
                && names.contains("claim") && names.contains("rest") && names.contains("say"),
                "mod catalog must serve the S0 surface, got: " + names);
        helper.assertTrue(sidecar.isAlive(), "sidecar transport should be alive after handshake");
        sidecar.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mcpDecideClaimsThroughSurface(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        WorkshopAgentEntity agent = spawnAgent(helper);
        TaskBoard board = AgentWorldData.board(level);
        board.raiseIfAbsent("gametest:mcp:claim", BoardTask.Type.BUILD, "mcp test build", level.getGameTime());

        FakeSidecarPeer peer = new FakeSidecarPeer(FakeSidecarPeer.MODE_DECIDE_CLAIM, agent.getUUID());
        McpSidecar sidecar = startSidecar(helper, peer);

        JsonObject args = new JsonObject();
        args.addProperty("agent", agent.getUUID().toString());
        AtomicReference<CompletableFuture<McpSidecar.CallResult>> ref = new AtomicReference<>();
        ref.set(sidecar.callTool("decide", args, 30));

        helper.runAfterDelay(40, () -> {
            try {
                CompletableFuture<McpSidecar.CallResult> future = ref.get();
                helper.assertTrue(future.isDone(), "decide never completed");
                McpSidecar.CallResult result = future.get(1, TimeUnit.SECONDS);
                helper.assertTrue(result.usable(), "decide result must be usable: " + result.failure());
                JsonObject payload = payloadOf(result.json());
                helper.assertTrue("claim".equals(payload.get("decision").getAsString()),
                        "expected a claim decision, got: " + payload);
                helper.assertTrue("gametest:mcp:claim".equals(payload.get("task").getAsString()),
                        "decide must report the claimed key, got: " + payload);
                helper.assertTrue(peer.failure == null, "peer failure: " + peer.failure);
                // the production execution loop may already have resolved the claimed task
                // (the test task is intentionally not a valid build job) — so assert the
                // claim TOUCHED this board with our claimant, whatever its final status
                helper.assertTrue(board.tasks().stream().anyMatch(task ->
                        task.key().equals("gametest:mcp:claim") && agent.getUUID().equals(task.claimantUuid())),
                        "the MCP claim must be recorded on the real board with our claimant");
                sidecar.close();
                agent.discard();
                helper.succeed();
            } catch (Exception e) {
                helper.fail("mcpDecideClaimsThroughSurface assertion crashed: " + e);
            }
        });
    }

    @GameTest(template = "empty")
    public static void mcpRejectsMalformedCalls(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        WorkshopAgentEntity agent = spawnAgent(helper);
        AgentWorldData.board(level).raiseIfAbsent("gametest:mcp:reject", BoardTask.Type.BUILD,
                "mcp reject probe", level.getGameTime());

        FakeSidecarPeer peer = new FakeSidecarPeer(FakeSidecarPeer.MODE_REJECT_PROBES, agent.getUUID());
        McpSidecar sidecar = startSidecar(helper, peer);

        JsonObject args = new JsonObject();
        args.addProperty("agent", agent.getUUID().toString());
        AtomicReference<CompletableFuture<McpSidecar.CallResult>> ref = new AtomicReference<>();
        ref.set(sidecar.callTool("decide", args, 30));

        helper.runAfterDelay(40, () -> {
            try {
                helper.assertTrue(peer.probes.await(1, TimeUnit.SECONDS), "peer probes never finished");
                helper.assertTrue(peer.failure == null, "peer failure: " + peer.failure);
                helper.assertTrue(ref.get().isDone() && ref.get().get(1, TimeUnit.SECONDS).usable(),
                        "decide must complete usable after probes");
                // (no board-state assert here: the probes never claim, and the production
                // decision loop may template-claim open tasks for our idle agent — the reject
                // behavior is fully verified by the peer's expectReject calls above)
                AgentWorldData.board(level).clearIfOpen("gametest:mcp:reject");
                sidecar.close();
                agent.discard();
                helper.succeed();
            } catch (Exception e) {
                helper.fail("mcpRejectsMalformedCalls assertion crashed: " + e);
            }
        });
    }

    @GameTest(template = "empty")
    public static void mcpDeadSidecarFallsBack(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        WorkshopAgentEntity agent = spawnAgent(helper);
        TaskBoard board = AgentWorldData.board(level);
        board.raiseIfAbsent("gametest:mcp:fallback", BoardTask.Type.BUILD, "mcp fallback task",
                level.getGameTime());

        FakeSidecarPeer peer = new FakeSidecarPeer(FakeSidecarPeer.MODE_DIE_AFTER_HANDSHAKE, null);
        McpSidecar sidecar = startSidecar(helper, peer);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sidecar.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        helper.assertTrue(!sidecar.isAlive(), "sidecar must be dead after the peer closes the pipe");

        // the same reconciliation the decision loop runs on an unusable MCP result; filtered
        // to this test's key — openTasks is global to the shared gametest world, and other
        // tests legitimately raise open build tasks in the same batch
        List<BoardTask> open = AgentDecisionLoop.openTasks(level, board).stream()
                .filter(task -> task.key().equals("gametest:mcp:fallback"))
                .toList();
        AgentDecisionLoop.handleMcpResult(level, agent, board, open,
                Config.AGENTS_TASK_LEASE_TICKS.get(), McpSidecar.CallResult.failed("sidecar_dead"));

        helper.assertTrue(board.tasks().stream()
                .anyMatch(task -> task.key().equals("gametest:mcp:fallback")
                        && agent.getUUID().equals(task.claimantUuid())),
                "fallback must claim the open task when the sidecar is dead");
        sidecar.close();
        agent.discard();
        helper.succeed();
    }

    /**
     * The gate ORDER is the S1 contract: {@code agents.mcp.enabled} alone (llm.enabled off)
     * must route through the sidecar — this regression shipped once with the MCP check below
     * the llm.enabled early-return. ModConfigSpec values bake at JVM start, so this pins the
     * pure routing function instead of toggling flags mid-test.
     */
    @GameTest(template = "empty")
    public static void mcpGateOrderPrecedence(GameTestHelper helper) {
        helper.assertTrue(AgentDecisionLoop.route(true, true, false) == AgentDecisionLoop.DecisionRoute.MCP,
                "mcp flag alone (llm disabled) must route through the sidecar");
        helper.assertTrue(AgentDecisionLoop.route(true, true, true) == AgentDecisionLoop.DecisionRoute.MCP,
                "mcp flag must take precedence over the direct llm path");
        helper.assertTrue(AgentDecisionLoop.route(true, false, true) == AgentDecisionLoop.DecisionRoute.DIRECT_LLM,
                "flag on but dead sidecar falls through to the direct path");
        helper.assertTrue(AgentDecisionLoop.route(true, false, false) == AgentDecisionLoop.DecisionRoute.TEMPLATE,
                "flag on but dead sidecar with llm disabled falls back to templates");
        helper.assertTrue(AgentDecisionLoop.route(false, true, true) == AgentDecisionLoop.DecisionRoute.DIRECT_LLM,
                "flag off never uses the sidecar; llm enabled picks the direct path");
        helper.assertTrue(AgentDecisionLoop.route(false, false, false) == AgentDecisionLoop.DecisionRoute.TEMPLATE,
                "everything off uses templates");
        helper.succeed();
    }

    /**
     * The S1 live run caught COMFORT_SHELTER leaking through openTasks() into observe_board
     * and claim BEFORE it had an executor — the model claimed it and the executor instantly
     * released it (ping-pong). The executor now exists (canopy + bed), so the task is a
     * first-class citizen of the board: it must be listed by observe_board and claimable by
     * key. The mock player keeps the shelter key's owner online.
     */
    @GameTest(template = "empty")
    public static void mcpShelterVisibleAndClaimable(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // Outside the template's barrier shell (rel -1..3), like the executor test.
        BlockPos playerPos = helper.absolutePos(new BlockPos(10, 1, 10));
        player.moveTo(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5, 0.0F, 0.0F);
        WorkshopAgentEntity agent = spawnAgent(helper);
        // The executor starts working the moment the claim lands; give it materials and
        // spawn it within arrival distance so it builds the canopy and HOLDS the claim
        // (an unstocked or far agent would release it again before the assert below).
        BlockPos agentSpawn = helper.absolutePos(new BlockPos(12, 1, 10));
        agent.moveTo(agentSpawn.getX() + 0.5, agentSpawn.getY(), agentSpawn.getZ() + 0.5, 0.0F, 0.0F);
        agent.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 16));
        agent.getInventory().setItem(1, new ItemStack(Items.RED_BED, 1));
        TaskBoard board = AgentWorldData.board(level);
        String key = "comfort:shelter:" + player.getUUID();

        // the peer's uuid arg is the SHELTER KEY OWNER in this mode (the decide "agent" arg
        // still carries the agent uuid, as the real sidecar does)
        FakeSidecarPeer peer = new FakeSidecarPeer(FakeSidecarPeer.MODE_SHELTER_PROBE, player.getUUID());
        McpSidecar sidecar = startSidecar(helper, peer);

        JsonObject args = new JsonObject();
        args.addProperty("agent", agent.getUUID().toString());
        AtomicReference<CompletableFuture<McpSidecar.CallResult>> ref = new AtomicReference<>();

        // The mock player only lands in level.players() a tick after join; openTasks drops
        // a shelter task whose owner is offline, so the raise + the decide dispatch both
        // happen in the delayed phase. (The production night detector may already have
        // raised this key — raiseIfAbsent is idempotent on it.)
        helper.runAfterDelay(40, () -> {
            try {
                board.raiseIfAbsent(key, BoardTask.Type.COMFORT_SHELTER, "shelter probe",
                        level.getGameTime());
                ref.set(sidecar.callTool("decide", args, 30));
            } catch (Throwable t) {
                level.getServer().getPlayerList().remove(player);
                agent.discard();
                sidecar.close();
                throw t;
            }
        });

        helper.runAfterDelay(80, () -> {
            try {
                McpSidecar.CallResult result = ref.get().get(1, TimeUnit.SECONDS);
                helper.assertTrue(result.usable(), "decide must complete usable: " + result.failure());
                JsonObject payload = payloadOf(result.json());
                // The payload is the deterministic contract: the peer listed the board
                // (shelter must be VISIBLE), claimed BY KEY (must be CLAIMABLE), and
                // echoes the claimed key. Board-state asserts stay out of this test —
                // 42 concurrent tests share the level and the production loop moves
                // claims between ticks.
                helper.assertTrue("claim".equals(payload.get("decision").getAsString()),
                        "claiming the now-executable shelter task must succeed, got: " + payload);
                helper.assertTrue(payload.has("task") && key.equals(payload.get("task").getAsString()),
                        "the claimed key must be the shelter key: " + payload);
                helper.assertTrue(peer.failure == null, "peer failure: " + peer.failure);
                board.clearIfOpen(key);
                sidecar.close();
                agent.discard();
                level.getServer().getPlayerList().remove(player);
                helper.succeed();
            } catch (Throwable t) {
                level.getServer().getPlayerList().remove(player);
                board.clearIfOpen(key);
                agent.discard();
                sidecar.close();
                helper.fail("mcpShelterVisibleAndClaimable assertion crashed: " + t);
            }
        });
    }

    // --- helpers --------------------------------------------------------------------

    private static McpSidecar startSidecar(GameTestHelper helper, FakeSidecarPeer peer) throws Exception {
        PipedOutputStream modWrites = new PipedOutputStream();
        PipedInputStream peerReads = new PipedInputStream(modWrites);
        PipedOutputStream peerWrites = new PipedOutputStream();
        PipedInputStream modReads = new PipedInputStream(peerWrites);
        peer.bind(peerReads, peerWrites);
        Thread thread = new Thread(peer, "gametest-fake-sidecar");
        thread.setDaemon(true);
        thread.start();
        McpSidecar sidecar = new McpSidecar(modReads, modWrites);
        sidecar.attach(helper.getLevel().getServer());
        sidecar.begin();
        return sidecar;
    }

    private static JsonObject payloadOf(JsonObject callResult) {
        String text = callResult.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString();
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static WorkshopAgentEntity spawnAgent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorkshopAgentEntity agent = new WorkshopAgentEntity(SiasWorkshopEntities.WORKSHOP_AGENT.get(), level);
        BlockPos absolute = helper.absolutePos(new BlockPos(1, 1, 1));
        agent.moveTo(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(agent);
        helper.assertFalse(agent.isRemoved(), "agent failed to spawn");
        return agent;
    }

    /**
     * In-JVM stand-in for sidecar.py: speaks the client role over the test pipes. MODE_CATALOG_PROBE
     * lists the mod catalog and idles; MODE_DECIDE_CLAIM runs a full decide (observe -> board ->
     * claim); MODE_REJECT_PROBES verifies the mod rejects malformed calls (recorded, not claimed);
     * MODE_DIE_AFTER_HANDSHAKE closes the pipe right after the handshake.
     */
    private static final class FakeSidecarPeer implements Runnable {
        static final int MODE_CATALOG_PROBE = 0;
        static final int MODE_DECIDE_CLAIM = 1;
        static final int MODE_REJECT_PROBES = 2;
        static final int MODE_DIE_AFTER_HANDSHAKE = 3;
        static final int MODE_SHELTER_PROBE = 4;

        private final int mode;
        private final UUID agentUuid;
        private BufferedReader in;
        private BufferedWriter out;
        private int nextId = 100;
        private final CountDownLatch catalog = new CountDownLatch(1);
        private final CountDownLatch probes = new CountDownLatch(1);
        private final List<String> catalogNames = new ArrayList<>();
        private volatile String failure;

        FakeSidecarPeer(int mode, UUID agentUuid) {
            this.mode = mode;
            this.agentUuid = agentUuid;
        }

        void bind(java.io.InputStream readSide, java.io.OutputStream writeSide) {
            this.in = new BufferedReader(new InputStreamReader(readSide, StandardCharsets.UTF_8));
            this.out = new BufferedWriter(new OutputStreamWriter(writeSide, StandardCharsets.UTF_8));
        }

        @Override
        public void run() {
            try {
                handshake();
                if (mode == MODE_DIE_AFTER_HANDSHAKE) {
                    out.close();
                    return;
                }
                if (mode == MODE_CATALOG_PROBE) {
                    JsonObject result = request("tools/list", new JsonObject());
                    JsonArray tools = result.getAsJsonArray("tools");
                    for (int i = 0; i < tools.size(); i++) {
                        catalogNames.add(tools.get(i).getAsJsonObject().get("name").getAsString());
                    }
                    catalog.countDown();
                }
                serve();
            } catch (Exception e) {
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        private void handshake() throws Exception {
            JsonObject init = request("initialize", initParams());
            if (!init.has("serverInfo")) {
                throw new IllegalStateException("initialize result missing serverInfo: " + init);
            }
            send(json -> {
                json.addProperty("jsonrpc", "2.0");
                json.addProperty("method", "notifications/initialized");
            });
        }

        private JsonObject initParams() {
            JsonObject params = new JsonObject();
            params.addProperty("protocolVersion", "2025-06-18");
            params.add("capabilities", new JsonObject());
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", "gametest-fake-sidecar");
            clientInfo.addProperty("version", "1.0");
            params.add("clientInfo", clientInfo);
            return params;
        }

        private void serve() throws Exception {
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject msg = JsonParser.parseString(line.trim()).getAsJsonObject();
                if (!msg.has("method") || !msg.has("id")) {
                    continue;
                }
                String method = msg.get("method").getAsString();
                int id = msg.get("id").getAsInt();
                if ("tools/list".equals(method)) {
                    JsonObject params = msg.getAsJsonObject("params");
                    JsonObject result = McpTools.listDescriptor();
                    JsonArray tools = result.getAsJsonArray("tools");
                    for (int i = 0; i < tools.size(); i++) {
                        catalogNames.add(tools.get(i).getAsJsonObject().get("name").getAsString());
                    }
                    respond(id, result);
                    catalog.countDown();
                } else if ("tools/call".equals(method)) {
                    JsonObject params = msg.getAsJsonObject("params");
                    String name = params.get("name").getAsString();
                    if ("decide".equals(name)) {
                        handleDecide(id, params.getAsJsonObject("arguments"));
                    } else {
                        respondErrorContent(id, "unknown tool '" + name + "'");
                    }
                }
            }
        }

        private void handleDecide(int id, JsonObject args) throws Exception {
            if (mode == MODE_REJECT_PROBES) {
                probeRejects();
                respondContent(id, summary("probes", "ok"));
                probes.countDown();
                return;
            }
            if (mode == MODE_SHELTER_PROBE) {
                shelterProbe(id, args);
                return;
            }
            String agent = args.get("agent").getAsString();
            JsonObject selfArgs = new JsonObject();
            selfArgs.addProperty("agent", agent);
            JsonObject obs = callMod("observe_self", selfArgs);
            if (!obs.has("observation") || !obs.get("observation").getAsString().contains("[SELF]")) {
                throw new IllegalStateException("observe_self missing [SELF]: " + obs);
            }
            JsonObject board = callMod("observe_board", new JsonObject());
            JsonArray open = board.getAsJsonArray("open_tasks");
            if (open == null || open.isEmpty()) {
                throw new IllegalStateException("expected open tasks on the board");
            }
            // the world (and board) persists across gametests in the batch — claim this
            // test's own key, never whatever happens to be listed first
            String key = "gametest:mcp:claim";
            boolean listed = false;
            for (int i = 0; i < open.size(); i++) {
                if (open.get(i).getAsString().startsWith(key + " — ")) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                throw new IllegalStateException("own task not visible on the board: " + board);
            }
            JsonObject claimArgs = new JsonObject();
            claimArgs.addProperty("agent", agent);
            claimArgs.addProperty("task", key);
            JsonObject claimed = callMod("claim", claimArgs);
            if (!claimed.has("claimed")) {
                throw new IllegalStateException("claim rejected: " + claimed);
            }
            JsonObject summary = summary("claim", claimed.get("claimed").getAsString());
            summary.addProperty("task", claimed.get("claimed").getAsString());
            respondContent(id, summary);
        }

        private void probeRejects() throws Exception {
            // say without msg → bad args; unknown tool; observe_self without agent → unknown agent
            expectReject(callModRaw("say", args -> args.addProperty("player", "Dev")));
            expectReject(callModRaw("dig", args -> {
            }));
            expectReject(callModRaw("observe_self", args -> {
            }));
        }

        /** MODE_SHELTER_PROBE: the raised shelter task (key owned by the mode's uuid arg) must be
         * listed by observe_board and claimable by key now that the task has an executor. */
        private void shelterProbe(int id, JsonObject args) throws Exception {
            String agent = args.get("agent").getAsString();
            JsonObject board = callMod("observe_board", new JsonObject());
            JsonArray open = board.getAsJsonArray("open_tasks");
            boolean listed = false;
            for (int i = 0; i < open.size(); i++) {
                if (open.get(i).getAsString().startsWith("comfort:shelter:")) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                throw new IllegalStateException("executable shelter task missing from the board: " + board);
            }
            JsonObject claimArgs = new JsonObject();
            claimArgs.addProperty("agent", agent);
            claimArgs.addProperty("task", "comfort:shelter:" + agentUuid);
            JsonObject claimed = callMod("claim", claimArgs);
            if (!claimed.has("claimed")) {
                throw new IllegalStateException("executable shelter claim must succeed, got: " + claimed);
            }
            JsonObject summary = summary("claim", claimed.get("claimed").getAsString());
            summary.addProperty("task", claimed.get("claimed").getAsString());
            respondContent(id, summary);
        }

        private JsonObject callMod(String name, JsonObject args) throws Exception {
            JsonObject body = callModRaw(name, args);
            if (body.has("reject")) {
                throw new IllegalStateException("mod rejected " + name + ": " + body);
            }
            return body;
        }

        private JsonObject callModRaw(String name, JsonBuilder builder) throws Exception {
            JsonObject args = new JsonObject();
            builder.build(args);
            return callModRaw(name, args);
        }

        private JsonObject callModRaw(String name, JsonObject args) throws Exception {
            int id = nextId++;
            JsonObject params = new JsonObject();
            params.addProperty("name", name);
            params.add("arguments", args);
            requestAsync(id, "tools/call", params);
            JsonObject response = awaitResponse(id);
            JsonObject result = response.getAsJsonObject("result");
            JsonArray content = result.getAsJsonArray("content");
            return JsonParser.parseString(content.get(0).getAsJsonObject().get("text").getAsString())
                    .getAsJsonObject();
        }

        private void expectReject(JsonObject body) {
            if (!body.has("reject")) {
                throw new IllegalStateException("expected a reject, got: " + body);
            }
        }

        private JsonObject summary(String decision, String detail) {
            JsonObject summary = new JsonObject();
            summary.addProperty("decision", decision);
            summary.addProperty("detail", detail);
            return summary;
        }

        private interface JsonBuilder {
            void build(JsonObject obj) throws Exception;
        }

        private JsonObject request(String method, JsonObject params) throws Exception {
            int id = nextId++;
            requestAsync(id, method, params);
            return awaitResponse(id).getAsJsonObject("result");
        }

        private void requestAsync(int id, String method, JsonObject params) throws Exception {
            JsonObject msg = new JsonObject();
            msg.addProperty("jsonrpc", "2.0");
            msg.addProperty("id", id);
            msg.addProperty("method", method);
            msg.add("params", params);
            send(msg);
        }

        private JsonObject awaitResponse(int id) throws Exception {
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject msg = JsonParser.parseString(line.trim()).getAsJsonObject();
                if (msg.has("id") && msg.get("id").getAsInt() == id) {
                    if (msg.has("error")) {
                        throw new IllegalStateException("rpc error: " + msg.get("error"));
                    }
                    return msg;
                }
            }
            throw new IllegalStateException("pipe closed waiting for response " + id);
        }

        private void respond(int id, JsonObject result) throws Exception {
            JsonObject msg = new JsonObject();
            msg.addProperty("jsonrpc", "2.0");
            msg.addProperty("id", id);
            msg.add("result", result);
            send(msg);
        }

        private void respondContent(int id, JsonObject body) throws Exception {
            JsonObject result = new JsonObject();
            result.addProperty("isError", false);
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", body.toString());
            content.add(text);
            result.add("content", content);
            respond(id, result);
        }

        private void respondErrorContent(int id, String reject) throws Exception {
            JsonObject result = new JsonObject();
            result.addProperty("isError", true);
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", "{\"reject\":\"" + reject + "\"}");
            content.add(text);
            result.add("content", content);
            respond(id, result);
        }

        private void send(JsonObject msg) throws Exception {
            out.write(msg.toString());
            out.write("\n");
            out.flush();
        }

        private void send(JsonBuilder builder) throws Exception {
            JsonObject msg = new JsonObject();
            builder.build(msg);
            send(msg);
        }
    }
}
