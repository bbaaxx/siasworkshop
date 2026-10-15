package com.siaws.siawsmod.content.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.config.Config;

import net.minecraft.server.MinecraftServer;

/**
 * Minimal OpenAI-compatible chat-completions client (java.net.http, no new dependencies —
 * design: one configurable base URL + model, any local runtime). Design principles from
 * reference/local-llm-feasibility: constrained JSON, defensive parsing, dedicated executor,
 * {@code server.execute} hop-back, offline-first.
 *
 * <p>Every failure mode — unreachable endpoint, timeout, non-2xx, malformed body, missing
 * fields, unparseable JSON — collapses into one {@link LlmResult} with {@link #usable()}
 * false, so callers can fall back to templates silently. Nothing here throws into the game
 * and nothing logs above DEBUG.
 */
public final class LlmClient {
    /**
     * Outcome of one request: either a parsed JSON object (the model's content parsed
     * defensively — usually one small action object) or a short machine-ish failure reason.
     */
    public record LlmResult(@Nullable JsonObject json, @Nullable String failure) {
        public static LlmResult ok(JsonObject json) {
            return new LlmResult(json, null);
        }

        public static LlmResult failed(String failure) {
            return new LlmResult(null, failure);
        }

        public boolean usable() {
            return json != null;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "siasworkshop-llm");
        thread.setDaemon(true);
        return thread;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .executor(EXECUTOR)
            .build();

    private LlmClient() {}

    /**
     * Request using the current config, hopping back onto the server thread for the callback.
     * The callback always runs on the server thread, so game state may be touched inside it.
     */
    public static void request(MinecraftServer server, String system, String user, Consumer<LlmResult> callback) {
        requestRaw(Config.LLM_ENDPOINT.get(), Config.LLM_MODEL.get(), Config.LLM_TIMEOUT_SECONDS.get(),
                Config.LLM_MAX_TOKENS.get(), Config.LLM_TEMPERATURE.get().floatValue(), system, user)
                .thenAccept(result -> server.execute(() -> callback.accept(result)));
    }

    /**
     * The raw request path: no server and no hop-back, which keeps it directly testable
     * against a loopback stub. Explicit parameters instead of config so tests can inject.
     */
    public static CompletableFuture<LlmResult> requestRaw(String endpoint, String model, int timeoutSeconds,
            int maxTokens, float temperature, String system, String user) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        body.add("messages", messages);

        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
        } catch (RuntimeException e) {
            // malformed endpoint/timeout config must fail the request, never throw on the caller
            return CompletableFuture.completedFuture(LlmResult.failed("bad_endpoint"));
        }

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(LlmClient::extractContent)
                .exceptionally(e -> LlmResult.failed(rootCause(e).getClass().getSimpleName()));
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static LlmResult extractContent(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            return LlmResult.failed("http_" + response.statusCode());
        }
        JsonObject root;
        try {
            root = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            return LlmResult.failed("bad_body");
        }
        try {
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            JsonObject json = LlmActions.parseJsonObject(content);
            return json != null ? LlmResult.ok(json) : LlmResult.failed("bad_json");
        } catch (RuntimeException e) {
            return LlmResult.failed("missing_fields");
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Debug-only visibility into failures; the design says never log-spam above DEBUG. */
    public static void logFailure(LlmResult result) {
        if (!result.usable()) {
            SiasWorkshop.LOGGER.debug("[LlmClient] request failed, falling back to templates: {}", result.failure());
        }
    }
}
