package com.siaws.siawsmod.content.agents.build;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import com.siaws.siawsmod.content.agents.AgentWorldData;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The build queue: how blueprint builds become board tasks. v1 enqueue path is the
 * {@code /siasworkshop build} command (a GUI on the village foundation block is deliberately
 * deferred). Session-scoped manager — it owns no state of its own: the single task store is
 * the {@link TaskBoard} (a BUILD task is a normal singleton board task keyed by
 * {@code build:<name>:<anchor>}). The job description travels in the task detail as
 * {@code blueprint=<name> anchor=<x>,<y>,<z>}, which the execution loop parses back.
 */
public final class BuildQueue {
    /** Detail prefix encoding the job; kept parseable-by-construction for the executor. */
    private static final String DETAIL_FORMAT = "blueprint=%s anchor=%d,%d,%d";

    private BuildQueue() {}

    /**
     * Enqueues a build of {@code blueprintName} at {@code anchor}. Returns the task when it
     * exists afterwards (new or already-raised), or null when the blueprint is unknown —
     * callers surface that feedback in chat.
     */
    @Nullable
    public static BoardTask enqueue(ServerLevel level, String blueprintName, BlockPos anchor) {
        Blueprint blueprint = BlueprintLibrary.INSTANCE.get(blueprintName);
        if (blueprint == null) {
            return null;
        }
        String name = blueprint.name();
        String key = "build:" + name + ":" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
        String detail = DETAIL_FORMAT.formatted(name, anchor.getX(), anchor.getY(), anchor.getZ());
        return AgentWorldData.board(level).raiseIfAbsent(key, BoardTask.Type.BUILD, detail, level.getGameTime());
    }

    /** A parsed build job. */
    public record BuildJob(String blueprintName, BlockPos anchor) {}

    /**
     * Parses the job description back out of a BUILD task's key/detail. Returns null when the
     * task is not a BUILD task or the description is malformed — the caller then fails the
     * task as {@code malformed_job}.
     */
    @Nullable
    public static BuildJob parseJob(BoardTask task) {
        if (task.type() != BoardTask.Type.BUILD) {
            return null;
        }
        String detail = task.detail();
        String blueprint = null;
        BlockPos anchor = null;
        for (String field : detail.split(" ")) {
            if (field.startsWith("blueprint=")) {
                blueprint = field.substring("blueprint=".length());
            } else if (field.startsWith("anchor=")) {
                anchor = parsePos(field.substring("anchor=".length()));
            }
        }
        if (blueprint == null || blueprint.isEmpty() || anchor == null) {
            return null;
        }
        return new BuildJob(blueprint, anchor);
    }

    @Nullable
    private static BlockPos parsePos(String value) {
        String[] parts = value.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Lowercase tokens of the blueprint name, used as style-tag candidates for card retrieval. */
    public static Set<String> nameTokens(String blueprintName) {
        Set<String> tokens = new HashSet<>();
        for (String token : blueprintName.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
