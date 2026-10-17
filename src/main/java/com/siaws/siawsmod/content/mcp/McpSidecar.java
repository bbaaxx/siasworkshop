package com.siaws.siawsmod.content.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.config.Config;

import net.minecraft.server.MinecraftServer;

/**
 * MCP sidecar transport (design: reference/mcp-pattern-tree-design D1/D2). The mod is the
 * MCP <b>server</b> (it owns the tool registry); the sidecar is a subprocess speaking the
 * MCP <b>client</b> role over its stdio — symmetric newline-delimited JSON-RPC: the sidecar
 * writes requests on its stdout (we read them here), we write requests on its stdin.
 *
 * <p>Both peers act as client AND server on the same pipes (D2 extension): the sidecar calls
 * mod tools ({@code observe_self}, {@code claim}, ...) and the mod calls the sidecar's
 * {@code decide} tool. All mod-tool dispatch is queued onto the server thread and the reader
 * parks until it completes — the server thread never blocks waiting on the sidecar (decisions
 * are async end-to-end, same shape as {@code LlmClient.request}), so the duplex flow cannot
 * deadlock.
 *
 * <p>Every failure mode — spawn failure, dead process, EOF, timeout, JSON-RPC error — collapses
 * into {@link CallResult} with {@code usable() false} so callers fall back to templates.
 * The instance is a server-singleton ({@link #startFor}/{@link #stop}); pure-stream instances
 * (no process) exist for gametests.
 */
public final class McpSidecar {
    /** Outcome of one outbound tools/call, mirroring LlmClient.LlmResult's failure collapse. */
    public record CallResult(@Nullable JsonObject json, @Nullable String failure) {
        public static CallResult ok(JsonObject json) {
            return new CallResult(json, null);
        }

        public static CallResult failed(String failure) {
            return new CallResult(null, failure);
        }

        public boolean usable() {
            return json != null;
        }
    }

    private static final AtomicInteger IDS = new AtomicInteger(1);
    private static @Nullable McpSidecar instance;

    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final @Nullable Process process;
    private final Map<Integer, CompletableFuture<CallResult>> pending = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private volatile @Nullable MinecraftServer server;
    private volatile boolean dead;

    /** Stream-only instance (gametests); {@link #startFor} uses the process factory. */
    public McpSidecar(InputStream fromSidecar, OutputStream toSidecar) {
        this(fromSidecar, toSidecar, null);
    }

    private McpSidecar(InputStream fromSidecar, OutputStream toSidecar, @Nullable Process process) {
        this.reader = new BufferedReader(new InputStreamReader(fromSidecar, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(toSidecar, StandardCharsets.UTF_8));
        this.process = process;
    }

    // --- server-singleton lifecycle ------------------------------------------------

    /** Spawn + start the sidecar when the flag is on; no-op otherwise. Called on server starting. */
    public static synchronized void startFor(MinecraftServer server) {
        stop();
        if (!Config.AGENTS_MCP_ENABLED.get()) {
            return;
        }
        String command = Config.AGENTS_MCP_COMMAND.get().trim();
        if (command.isEmpty()) {
            SiasWorkshop.LOGGER.warn("[McpSidecar] agents.mcp.enabled but agents.mcp.command is empty; MCP path disabled");
            return;
        }
        try {
            McpSidecar started = spawn(command, server);
            instance = started;
            String pid = started.process == null ? "stream" : Long.toString(started.process.pid());
            SiasWorkshop.LOGGER.info("[McpSidecar] sidecar started (pid {}): {}", pid, command);
        } catch (RuntimeException e) {
            instance = null;
            SiasWorkshop.LOGGER.warn("[McpSidecar] failed to start sidecar '{}': {}; decisions fall back to templates",
                    command, e.toString());
        }
        // hot-plug debug listener rides the same lifecycle: it can only exist while the MCP
        // path is enabled, and a bind failure must never take the sidecar down with it
        McpDebugServer.startFor(server);
    }

    private static McpSidecar spawn(String command, MinecraftServer server) {
        ProcessBuilder builder = new ProcessBuilder(command.split(" "));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        McpSidecar sidecar = new McpSidecar(process.getInputStream(), process.getOutputStream(), process);
        sidecar.attach(server);
        sidecar.begin();
        return sidecar;
    }

    /** Current server singleton; null when the flag is off or the sidecar died/was never started. */
    public static synchronized @Nullable McpSidecar current() {
        McpSidecar sidecar = instance;
        if (sidecar == null || !sidecar.isAlive()) {
            return null;
        }
        return sidecar;
    }

    public static synchronized void stop() {
        McpDebugServer.stop();
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    /** Bind the server used to execute inbound mod-tool calls (server thread). */
    public void attach(MinecraftServer server) {
        this.server = server;
    }

    /** Begin the reader thread and await the initialize/initialized handshake. */
    public void begin() {
        Thread thread = new Thread(this::readLoop, "siasworkshop-mcp-sidecar");
        thread.setDaemon(true);
        thread.start();
        try {
            started.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            dead = true;
            SiasWorkshop.LOGGER.warn("[McpSidecar] handshake did not complete: {}", e.toString());
        }
    }

    public boolean isAlive() {
        return !dead && (process == null || process.isAlive());
    }

    // --- outbound (mod -> sidecar) --------------------------------------------------

    /**
     * Call a tool on the sidecar (currently {@code decide}). Async: the future completes on the
     * reader thread with the parsed result content or a machine-ish failure.
     */
    public CompletableFuture<CallResult> callTool(String name, JsonObject args, long timeoutSeconds) {
        if (!isAlive()) {
            return CompletableFuture.completedFuture(CallResult.failed("sidecar_dead"));
        }
        int id = IDS.getAndIncrement();
        CompletableFuture<CallResult> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", args);
        request.add("params", params);
        try {
            write(request);
        } catch (RuntimeException e) {
            pending.remove(id);
            return CompletableFuture.completedFuture(CallResult.failed("write_" + e.getClass().getSimpleName()));
        }
        return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    pending.remove(id);
                    return e instanceof java.util.concurrent.TimeoutException
                            ? CallResult.failed("timeout")
                            : CallResult.failed(e.getClass().getSimpleName());
                });
    }

    // --- reader ---------------------------------------------------------------------

    private void readLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                JsonObject msg;
                try {
                    msg = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue; // skip unparseable lines, keep the pipe flowing
                }
                if (msg.has("method")) {
                    handleInbound(msg);
                } else if (msg.has("id")) {
                    CompletableFuture<CallResult> future = pending.remove(msg.get("id").getAsInt());
                    if (future != null) {
                        if (msg.has("error")) {
                            future.complete(CallResult.failed("rpc_error"));
                        } else {
                            future.complete(CallResult.ok(msg.getAsJsonObject("result")));
                        }
                    }
                }
            }
        } catch (IOException e) {
            // EOF / pipe close: fall through to kill
        } finally {
            die("eof");
        }
    }

    /** Inbound request/notification from the sidecar (client role). */
    private void handleInbound(JsonObject msg) {
        String method = msg.get("method").getAsString();
        if (method.equals("notifications/initialized")) {
            started.complete(null);
            return;
        }
        if (!msg.has("id")) {
            return; // other notifications: ignore
        }
        int id = msg.get("id").getAsInt();
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                ? msg.getAsJsonObject("params") : new JsonObject();
        switch (method) {
            case "initialize" -> respond(id, initializeResult(params));
            case "tools/list" -> respond(id, McpTools.listDescriptor());
            case "tools/call" -> handleSidecarToolCall(id, params);
            default -> respondError(id, -32601, "method not found: " + method);
        }
    }

    private JsonObject initializeResult(JsonObject params) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion",
                params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "2025-06-18");
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "siasworkshop");
        serverInfo.addProperty("version", "s1");
        result.add("serverInfo", serverInfo);
        return result;
    }

    /** The sidecar called a mod tool: run on the server thread, park the reader until done. */
    private void handleSidecarToolCall(int id, JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        MinecraftServer bound = server;
        if (bound == null) {
            respondErrorContent(id, "mod not attached");
            return;
        }
        CompletableFuture<JsonObject> dispatch = new CompletableFuture<>();
        bound.execute(() -> {
            try {
                dispatch.complete(McpTools.dispatch(bound, name, args));
            } catch (RuntimeException e) {
                JsonObject reject = new JsonObject();
                reject.addProperty("reject", e.getMessage() != null ? e.getMessage() : e.toString());
                dispatch.complete(reject);
            }
        });
        JsonObject result;
        try {
            JsonObject body = dispatch.get(60, TimeUnit.SECONDS);
            boolean isError = body.has("reject");
            result = new JsonObject();
            result.addProperty("isError", isError);
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", body.toString());
            content.add(text);
            result.add("content", content);
        } catch (Exception e) {
            respondErrorContent(id, "dispatch_timeout:" + e.getClass().getSimpleName());
            return;
        }
        respond(id, result);
    }

    private void respond(int id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", id);
        response.add("result", result);
        write(response);
    }

    private void respondError(int id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        write(response);
    }

    private void respondErrorContent(int id, String reject) {
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

    private synchronized void write(JsonObject msg) {
        try {
            writer.write(msg.toString());
            writer.write("\n");
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void die(String reason) {
        dead = true;
        CallResult failure = CallResult.failed("sidecar_" + reason);
        pending.values().forEach(f -> f.complete(failure));
        pending.clear();
        started.completeExceptionally(new RuntimeException(reason));
    }

    public void close() {
        dead = true;
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        try {
            reader.close();
        } catch (IOException ignored) {
        }
        if (process != null) {
            process.destroy();
        }
        pending.values().forEach(f -> f.complete(CallResult.failed("sidecar_closed")));
        pending.clear();
    }
}
