package com.siaws.siawsmod.content.agents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Agent state visibility (user feedback 2026-10-16, ledger SEQ-015/016): renders a
 * short human-readable state on the agent's nameplate — deciding… / working · task
 * (heading there | fetching materials | placing) / resting / idle — driven by the
 * real signals of {@link AgentDecisionLoop}, so a watching player can tell what the
 * agent is doing during long LLM pauses.
 *
 * <p>Also owns the two other visibility sinks: the {@code say} action's chat line
 * (a chatty model is otherwise invisible — v1 set a label and showed nothing), and
 * the config-gated DEBUG activity log ({@code agents.debugActivityLog}): a JSONL
 * trace of decisions, claims, and state transitions appended under
 * {@code debug/siasworkshop/agent-activity.log} so the coordinator can read what an
 * agent did, and why, straight from the repo.
 */
public final class AgentActivity {
    private static final String LOG_DIR = "debug/siasworkshop";
    private static final String LOG_FILE = "agent-activity.log";

    private AgentActivity() {}

    // --- nameplate ------------------------------------------------------------

    /** Phase while an LLM decision request is in flight. */
    public static void deciding(WorkshopAgentEntity agent) {
        agent.setActivityLabel("deciding…");
    }

    /** Claimed-task state; phase is null, or one of heading there/placing/fetching materials. */
    public static void working(WorkshopAgentEntity agent, BoardTask task, @Nullable String phase) {
        agent.setActivityLabel(prettyTask(task) + (phase == null ? "" : " · " + phase));
    }

    /** No claim, nothing in flight. */
    public static void idle(WorkshopAgentEntity agent) {
        agent.setActivityLabel("");
    }

    /** Deliberate idle chosen by the model (rest decision). */
    public static void resting(WorkshopAgentEntity agent) {
        agent.setActivityLabel("resting");
    }

    /** Short human phrase for a task, for the nameplate. */
    public static String prettyTask(BoardTask task) {
        return switch (task.type()) {
            case COMFORT_LIGHT -> "comfort · lighting";
            case COMFORT_SHELTER -> "comfort · shelter";
            case BUILD -> "building" + buildName(task);
        };
    }

    /** Blueprint name out of the build task detail ("blueprint=<name> anchor=…"), "" when absent. */
    private static String buildName(BoardTask task) {
        String detail = task.detail();
        int start = detail.indexOf("blueprint=");
        if (start < 0) {
            return "";
        }
        int end = detail.indexOf(' ', start);
        return " " + (end < 0 ? detail.substring(start + "blueprint=".length()) : detail.substring(start + "blueprint=".length(), end));
    }

    // --- chat -------------------------------------------------------------------

    /** The {@code say} action, made visible: a chat line from the agent to the level's players. */
    public static void say(ServerLevel level, WorkshopAgentEntity agent, String player, String msg) {
        Component line = Component.translatable("chat.siasworkshop.agent_say", agent.getName(), msg);
        for (ServerPlayer p : level.players()) {
            p.sendSystemMessage(line);
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("to", player);
        data.put("msg", msg);
        log(level, agent, "say", data);
    }

    // --- debug activity log -------------------------------------------------------

    public static void dispatched(ServerLevel level, WorkshopAgentEntity agent, int openTasks) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("open_tasks", String.valueOf(openTasks));
        log(level, agent, "decide_dispatched", data);
    }

    public static void decision(ServerLevel level, WorkshopAgentEntity agent, String action, String args) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("action", action);
        data.put("args", args);
        log(level, agent, "llm_decision", data);
    }

    public static void fallback(ServerLevel level, WorkshopAgentEntity agent, String reason) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("reason", reason);
        log(level, agent, "llm_fallback", data);
    }

    public static void claimed(ServerLevel level, WorkshopAgentEntity agent, BoardTask task) {
        working(agent, task, null);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("key", task.key());
        data.put("type", task.type().name());
        log(level, agent, "claim", data);
    }

    /** One JSONL record: {"ts":…,"agent":"<uuid8>","event":…,"data":{…}}. Exposed for tests. */
    public static String line(long timestampMillis, WorkshopAgentEntity agent, String event, Map<String, String> data) {
        JsonObject root = new JsonObject();
        root.addProperty("ts", timestampMillis);
        root.addProperty("agent", agent.getUUID().toString().substring(0, 8));
        root.addProperty("event", event);
        if (data != null && !data.isEmpty()) {
            JsonObject fields = new JsonObject();
            data.forEach(fields::addProperty);
            root.add("data", fields);
        }
        return root.toString();
    }

    /** Append one line, creating the directory on first use. Exposed for tests. */
    public static void appendLine(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            SiasWorkshop.LOGGER.debug("[AgentActivity] failed to append activity log", e);
        }
    }

    private static void log(ServerLevel level, WorkshopAgentEntity agent, String event, Map<String, String> data) {
        if (!Config.AGENTS_DEBUG_ACTIVITY_LOG.get()) {
            return;
        }
        Path file = level.getServer().getFile(LOG_DIR).resolve(LOG_FILE);
        appendLine(file, line(System.currentTimeMillis(), agent, event, data));
    }
}
