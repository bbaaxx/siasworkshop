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

        // the same reconciliation the decision loop runs on an unusable MCP result
        List<BoardTask> open = AgentDecisionLoop.openTasks(level, board);
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
