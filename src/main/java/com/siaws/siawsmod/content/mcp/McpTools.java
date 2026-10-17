package com.siaws.siawsmod.content.mcp;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.content.agents.AgentActivity;
import com.siaws.siawsmod.content.agents.AgentDecisionLoop;
import com.siaws.siawsmod.content.agents.AgentObservation;
import com.siaws.siawsmod.content.agents.AgentWorldData;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The mod's MCP tool catalog and dispatch (design D3/D7): the stable model-facing surface,
 * mirroring the sidecar spike's S0 set. Every call is validated here — unknown tools, bad
 * args, unknown agent/task keys are rejected with a structured {@code {"reject": ...}} body
 * (surfaced as an MCP isError result) so the sidecar can correct and retry. All dispatch
 * runs on the server thread ({@link McpSidecar} queues it); the agent identity arrives as
 * an {@code agent} uuid argument so tools stay stateless and multi-agent safe.
 */
public final class McpTools {
    private McpTools() {}

    private static final String[][] TOOLS = {
            {"observe_self", "observe", "Read-only: this agent's compact situation snapshot.",
                    "agent"},
            {"observe_board", "observe", "Read-only: the shared task board — open tasks with exact claim keys.",
                    null},
            {"claim", "effect", "Start work on an OPEN TASK; hands (walk, place, light, build) then run automatically.",
                    "agent, task?"},
            {"rest", "effect", "Deliberately idle this cycle.", "agent"},
            {"say", "effect", "Speak one short line to a player (shows in their chat).", "agent, player, msg"},
    };

    /** tools/list response for the catalog the sidecar sees. */
    public static JsonObject listDescriptor() {
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();
        for (String[] tool : TOOLS) {
            JsonObject descriptor = new JsonObject();
            descriptor.addProperty("name", tool[0]);
            descriptor.addProperty("description", tool[2] + (tool[3] != null ? " Args: " + tool[3] : ""));
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            if (tool[3] != null) {
                for (String arg : tool[3].split(", ")) {
                    String key = arg.endsWith("?") ? arg.substring(0, arg.length() - 1) : arg;
                    properties.add(key, new JsonObject());
                }
            }
            schema.add("properties", properties);
            descriptor.add("inputSchema", schema);
            tools.add(descriptor);
        }
        result.add("tools", tools);
        return result;
    }

    /**
     * Execute one mod tool call. Returns the result body on success or {@code {"reject": ...}}
     * on any validation failure — never throws for model-visible problems.
     */
    public static JsonObject dispatch(MinecraftServer server, String name, JsonObject args) {
        return switch (name) {
            case "observe_self" -> observeSelf(server, args);
            case "observe_board" -> observeBoard(server);
            case "claim" -> claim(server, args);
            case "rest" -> rest(server, args);
            case "say" -> say(server, args);
            default -> reject("unknown tool '" + name + "'");
        };
    }

    private static JsonObject observeSelf(MinecraftServer server, JsonObject args) {
        WorkshopAgentEntity agent = agentOf(server, args);
        if (agent == null) {
            return reject("unknown agent");
        }
        ServerLevel level = (ServerLevel) agent.level();
        String observation = AgentObservation.build(agent, Config.AGENTS_OBSERVATION_BUDGET_TOKENS.get());
        JsonObject result = new JsonObject();
        result.addProperty("observation", observation);
        return result;
    }

    private static JsonObject observeBoard(MinecraftServer server) {
        JsonObject result = new JsonObject();
        JsonArray lines = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            TaskBoard board = AgentWorldData.board(level);
            for (BoardTask task : AgentDecisionLoop.openTasks(level, board)) {
                lines.add(task.key() + " — " + task.detail());
            }
        }
        result.add("open_tasks", lines);
        return result;
    }

    private static JsonObject claim(MinecraftServer server, JsonObject args) {
        WorkshopAgentEntity agent = agentOf(server, args);
        if (agent == null) {
            return reject("unknown agent");
        }
        ServerLevel level = (ServerLevel) agent.level();
        TaskBoard board = AgentWorldData.board(level);
        List<BoardTask> open = AgentDecisionLoop.openTasks(level, board);
        BoardTask target = null;
        if (args.has("task") && args.get("task").isJsonPrimitive()) {
            String key = args.get("task").getAsString();
            target = open.stream().filter(task -> task.key().equals(key)).findFirst().orElse(null);
            if (target == null) {
                return reject("unknown or claimed task key '" + key + "'");
            }
        } else if (!open.isEmpty()) {
            target = AgentDecisionLoop.highestPriority(open);
        }
        if (target == null) {
            return reject("no open tasks to claim");
        }
        long now = level.getGameTime();
        boolean claimed = board.claim(target.id(), agent.getUUID(),
                Config.AGENTS_TASK_LEASE_TICKS.get(), now);
        if (!claimed) {
            return reject("claim raced, task no longer open");
        }
        agent.setCurrentTaskLabel(target.key());
        AgentActivity.claimed(level, agent, target);
        JsonObject result = new JsonObject();
        result.addProperty("claimed", target.key());
        return result;
    }

    private static JsonObject rest(MinecraftServer server, JsonObject args) {
        WorkshopAgentEntity agent = agentOf(server, args);
        if (agent == null) {
            return reject("unknown agent");
        }
        agent.setCurrentTaskLabel("rest");
        AgentActivity.resting(agent);
        JsonObject result = new JsonObject();
        result.addProperty("resting", true);
        return result;
    }

    private static JsonObject say(MinecraftServer server, JsonObject args) {
        WorkshopAgentEntity agent = agentOf(server, args);
        if (agent == null) {
            return reject("unknown agent");
        }
        if (!stringArg(args, "player") || !stringArg(args, "msg")) {
            return reject("bad args for say: need string player and msg");
        }
        ServerLevel level = (ServerLevel) agent.level();
        AgentActivity.say(level, agent, args.get("player").getAsString(), args.get("msg").getAsString());
        JsonObject result = new JsonObject();
        result.addProperty("said", args.get("msg").getAsString());
        return result;
    }

    // --- helpers --------------------------------------------------------------------

    private static @javax.annotation.Nullable WorkshopAgentEntity agentOf(MinecraftServer server, JsonObject args) {
        if (!args.has("agent") || !args.get("agent").isJsonPrimitive()) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(args.get("agent").getAsString());
        } catch (RuntimeException e) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof WorkshopAgentEntity agent) {
                return agent;
            }
        }
        return null;
    }

    private static boolean stringArg(JsonObject args, String key) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive()) {
            return false;
        }
        try {
            args.get(key).getAsString();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static JsonObject reject(String message) {
        JsonObject reject = new JsonObject();
        reject.addProperty("reject", message);
        return reject;
    }
}
