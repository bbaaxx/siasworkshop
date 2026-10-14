package com.siaws.siawsmod.content.agents;

import com.siaws.siawsmod.content.agents.pool.AgentDecisionPool;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;

import net.minecraft.server.level.ServerLevel;

/**
 * Single access pattern for the per-world agent stores ({@link AgentDecisionPool},
 * {@link TaskBoard}): everything goes through {@code level.getDataStorage()} with the
 * constants owned by each SavedData class. Tests reach for the SavedData classes directly;
 * runtime code (detectors, inspector, future LLM loop) goes through here.
 */
public final class AgentWorldData {
    private AgentWorldData() {}

    public static AgentDecisionPool pool(ServerLevel level) {
        return AgentDecisionPool.get(level);
    }

    public static TaskBoard board(ServerLevel level) {
        return TaskBoard.get(level);
    }
}
