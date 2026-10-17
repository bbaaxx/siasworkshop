package com.siaws.siawsmod.gametest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.siaws.siawsmod.content.agents.AgentWorldData;
import com.siaws.siawsmod.content.mcp.McpDebugServer;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Game tests for the hot-plug debug attach transport ({@link McpDebugServer}, initiative
 * mcp-hotplug-debug-access P2). The debug listener must (a) never start unless the MCP path is
 * enabled AND a port is configured — the pure {@link McpDebugServer#shouldStart} gate is pinned
 * because ModConfigSpec bakes at JVM start, same stance as the agents.mcp.enabled tests — and
 * (b) serve only the read-only observe catalog over loopback, rejecting write tools even when
 * invoked directly by name.
 */
@GameTestHolder("siasworkshop")
public final class McpDebugGameTest {
    private McpDebugGameTest() {}

    @GameTest(template = "empty")
    public static void debugGateRequiresMcpEnabledAndPort(GameTestHelper helper) {
        helper.assertFalse(McpDebugServer.shouldStart(false, 25566),
                "debug listener must never spawn when agents.mcp.enabled is off");
        helper.assertFalse(McpDebugServer.shouldStart(true, 0),
                "debug listener must stay off when no port is configured");
        helper.assertTrue(McpDebugServer.shouldStart(true, 25566),
                "debug listener starts only with the MCP path on AND a port set");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void debugServerServesReadOnlyCatalog(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        AgentWorldData.board(level).raiseIfAbsent("gametest:debug:probe",
                com.siaws.siawsmod.content.agents.tasks.BoardTask.Type.BUILD,
                "debug transport probe", level.getGameTime());

        McpDebugServer debug = new McpDebugServer(level.getServer(), 0); // ephemeral port
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<List<String>> catalog = new AtomicReference<>(new ArrayList<>());
        AtomicReference<Boolean> boardOk = new AtomicReference<>(false);
        Thread client = new Thread(() -> {
            try {
                ProbeClient probe = new ProbeClient(debug.port());
                probe.initialize();
                catalog.set(probe.toolNames());
                JsonObject board = probe.call("observe_board", new JsonObject());
                boolean ok = !board.has("reject") && board.has("open_tasks")
                        && board.getAsJsonArray("open_tasks").toString().contains("gametest:debug:probe");
                boardOk.set(ok);
                // write tools are not on the catalog; invoking one by name must still be rejected
                JsonObject claimArgs = new JsonObject();
                claimArgs.addProperty("agent", "00000000-0000-0000-0000-000000000000");
                JsonObject claim = probe.call("claim", claimArgs);
                if (!claim.has("reject")) {
                    throw new IllegalStateException("claim must be rejected on the debug transport: " + claim);
                }
                probe.close();
            } catch (Exception e) {
                failure.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                done.countDown();
            }
        }, "gametest-debug-probe");
        client.setDaemon(true);
        client.start();

        helper.runAfterDelay(40, () -> {
            try {
                helper.assertTrue(done.await(10, TimeUnit.SECONDS), "debug probe never finished");
                helper.assertTrue(failure.get() == null, "debug probe failure: " + failure.get());
                helper.assertTrue(boardOk.get(), "observe_board must list the probe task");
                List<String> names = catalog.get();
                helper.assertTrue(names.contains("observe_self") && names.contains("observe_board"),
                        "read-only catalog must serve the observe tools, got: " + names);
                helper.assertFalse(names.contains("claim") || names.contains("rest") || names.contains("say"),
                        "write tools must never appear on the debug catalog, got: " + names);
                debug.close();
                AgentWorldData.board(level).clearIfOpen("gametest:debug:probe");
                helper.succeed();
            } catch (Exception e) {
                helper.fail("debugServerServesReadOnlyCatalog assertion crashed: " + e);
            }
        });
    }

    /** Minimal in-JVM MCP client speaking the debug server role: request by id, one connection. */
    private static final class ProbeClient {
        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;
        private int nextId = 1;

        ProbeClient(int port) throws IOException {
            this.socket = new Socket(InetAddress.getByName("127.0.0.1"), port);
            this.socket.setSoTimeout(10_000);
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void initialize() throws IOException {
            JsonObject params = new JsonObject();
            params.addProperty("protocolVersion", "2025-06-18");
            params.add("capabilities", new JsonObject());
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", "gametest-debug-probe");
            clientInfo.addProperty("version", "1.0");
            params.add("clientInfo", clientInfo);
            JsonObject result = request("initialize", params);
            if (!result.has("serverInfo")) {
                throw new IllegalStateException("initialize result missing serverInfo: " + result);
            }
        }

        List<String> toolNames() throws IOException {
            JsonObject result = request("tools/list", new JsonObject());
            JsonArray tools = result.getAsJsonArray("tools");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < tools.size(); i++) {
                names.add(tools.get(i).getAsJsonObject().get("name").getAsString());
            }
            return names;
        }

        /** tools/call, returning the parsed body (has "reject" when the mod rejected the call). */
        JsonObject call(String name, JsonObject args) throws IOException {
            JsonObject params = new JsonObject();
            params.addProperty("name", name);
            params.add("arguments", args);
            JsonObject result = request("tools/call", params);
            JsonArray content = result.getAsJsonArray("content");
            return JsonParser.parseString(content.get(0).getAsJsonObject().get("text").getAsString())
                    .getAsJsonObject();
        }

        void close() throws IOException {
            socket.close();
        }

        private JsonObject request(String method, JsonObject params) throws IOException {
            int id = nextId++;
            JsonObject msg = new JsonObject();
            msg.addProperty("jsonrpc", "2.0");
            msg.addProperty("id", id);
            msg.addProperty("method", method);
            msg.add("params", params);
            out.write(msg.toString());
            out.write("\n");
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject reply = JsonParser.parseString(line.trim()).getAsJsonObject();
                if (reply.has("id") && reply.get("id").getAsInt() == id) {
                    if (reply.has("error")) {
                        throw new IllegalStateException("rpc error: " + reply.get("error"));
                    }
                    return reply.getAsJsonObject("result");
                }
            }
            throw new IllegalStateException("debug server closed the connection mid-request");
        }
    }
}
