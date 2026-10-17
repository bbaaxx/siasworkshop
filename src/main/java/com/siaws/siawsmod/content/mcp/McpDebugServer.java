package com.siaws.siawsmod.content.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.config.Config;

import net.minecraft.server.MinecraftServer;

/**
 * Hot-plug debug attach transport (reference/mcp-pattern-tree-design, debug channel; initiative
 * mcp-hotplug-debug-access P2). When {@code agents.mcp.enabled} is on AND
 * {@code agents.mcp.debugPort > 0}, the mod listens on {@code 127.0.0.1:<port>} speaking the
 * same newline-delimited JSON-RPC server role as the sidecar stdio channel, but serving a
 * <b>read-only</b> catalog ({@code observe_self}/{@code observe_board} via
 * {@link McpTools#listDescriptor(boolean)}) against the live board. Attach with
 * {@code tools/mcp_probe.py} or any MCP client; no game restart needed.
 *
 * <p>Security stance: loopback bind only, config-gated (default OFF), started only by
 * {@link McpSidecar#startFor} so it can never spawn when the MCP path is off, and write tools
 * are rejected by both the filtered catalog and an explicit {@link McpTools#isReadOnly} guard
 * before dispatch. Thread-per-connection; all tool dispatch runs on the server thread, the
 * same shape as {@link McpSidecar}'s inbound handling.
 */
public final class McpDebugServer {
    private static final Set<Socket> OPEN = ConcurrentHashMap.newKeySet();
    private static @Nullable McpDebugServer instance;

    /**
     * Pure start gate, pinned by the gametest: the debug transport must never open a listener
     * unless the MCP path itself is enabled and a port is configured (ModConfigSpec bakes at
     * JVM start, so gating is verified on this predicate plus live runs, as with
     * {@code agents.mcp.enabled}).
     */
    public static boolean shouldStart(boolean mcpEnabled, int port) {
        return mcpEnabled && port > 0;
    }

    /** Start the debug listener when the gate allows; no-op otherwise. Never throws. */
    public static synchronized void startFor(MinecraftServer server) {
        stop();
        if (!shouldStart(Config.AGENTS_MCP_ENABLED.get(), Config.AGENTS_MCP_DEBUG_PORT.get())) {
            return;
        }
        try {
            instance = new McpDebugServer(server, Config.AGENTS_MCP_DEBUG_PORT.get());
            SiasWorkshop.LOGGER.info("[McpDebugServer] debug attach on 127.0.0.1:{}"
                    + " (read-only catalog: observe_self, observe_board)", instance.port());
        } catch (RuntimeException e) {
            instance = null;
            SiasWorkshop.LOGGER.warn("[McpDebugServer] debug listener failed to start: {}", e.toString());
        }
    }

    public static synchronized void stop() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    private final ServerSocket socket;
    private final MinecraftServer server;
    private volatile boolean running = true;

    /** Test-visible constructor; production goes through {@link #startFor}. */
    public McpDebugServer(MinecraftServer server, int port) {
        this.server = server;
        try {
            this.socket = new ServerSocket();
            this.socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Thread accept = new Thread(this::acceptLoop, "siasworkshop-mcp-debug-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                OPEN.add(client);
                Thread thread = new Thread(() -> serve(client), "siasworkshop-mcp-debug-client");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                if (running) {
                    SiasWorkshop.LOGGER.warn("[McpDebugServer] accept failed: {}", e.toString());
                }
                return;
            }
        }
    }

    /** One debug client connection: it drives (initialize -> tools/list -> tools/call), we answer. */
    private void serve(Socket client) {
        try (Socket sock = client;
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                JsonObject msg;
                try {
                    msg = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue; // skip unparseable lines, keep the socket flowing
                }
                if (!msg.has("method") || !msg.has("id")) {
                    continue; // notifications and stray responses: ignore
                }
                int id = msg.get("id").getAsInt();
                JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                        ? msg.getAsJsonObject("params") : new JsonObject();
                switch (msg.get("method").getAsString()) {
                    case "initialize" -> write(writer, initializeResult(id, params));
                    case "tools/list" -> write(writer, response(id, McpTools.listDescriptor(true)));
                    case "tools/call" -> write(writer, handleToolCall(id, params));
                    default -> write(writer, error(id, -32601,
                            "method not found: " + msg.get("method").getAsString()));
                }
            }
        } catch (IOException ignored) {
            // client went away
        } finally {
            OPEN.remove(client);
        }
    }

    /** Read-only tool call: observe_* dispatch on the server thread; anything else rejected. */
    private JsonObject handleToolCall(int id, JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        if (!McpTools.isReadOnly(name)) {
            return errorContent(id, "tool '" + name
                    + "' is not available on the read-only debug transport");
        }
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        CompletableFuture<JsonObject> dispatch = new CompletableFuture<>();
        server.execute(() -> {
            try {
                dispatch.complete(McpTools.dispatch(server, name, args));
            } catch (RuntimeException e) {
                JsonObject reject = new JsonObject();
                reject.addProperty("reject", e.getMessage() != null ? e.getMessage() : e.toString());
                dispatch.complete(reject);
            }
        });
        JsonObject body;
        try {
            body = dispatch.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            return errorContent(id, "dispatch_timeout:" + e.getClass().getSimpleName());
        }
        boolean isError = body.has("reject");
        JsonObject result = new JsonObject();
        result.addProperty("isError", isError);
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", body.toString());
        content.add(text);
        result.add("content", content);
        return response(id, result);
    }

    private JsonObject initializeResult(int id, JsonObject params) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion",
                params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "2025-06-18");
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "siasworkshop-debug");
        serverInfo.addProperty("version", "s1");
        result.add("serverInfo", serverInfo);
        return response(id, result);
    }

    private JsonObject response(int id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", id);
        response.add("result", result);
        return response;
    }

    private JsonObject error(int id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }

    private JsonObject errorContent(int id, String reject) {
        JsonObject result = new JsonObject();
        result.addProperty("isError", true);
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", "{\"reject\":\"" + reject + "\"}");
        content.add(text);
        result.add("content", content);
        return response(id, result);
    }

    private void write(BufferedWriter writer, JsonObject msg) throws IOException {
        writer.write(msg.toString());
        writer.write("\n");
        writer.flush();
    }

    /** Close the listener and all open debug connections. Idempotent; test-visible. */
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        for (Socket client : OPEN) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
        OPEN.clear();
    }
}
