package com.siaws.siawsmod.content.llm;

import java.util.List;

import com.siaws.siawsmod.content.agents.build.BuildCard;
import com.siaws.siawsmod.content.agents.pool.PoolEntry;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;

/**
 * Builds the agent decision prompt (reference/local-llm-feasibility: short prompts, grounded
 * facts only, constrained JSON out). The user message is the token-budgeted observation
 * snapshot plus small grounded extras: the validated action space, top-K pool entries as
 * one-liners, and — only when a build decision is pending — up to three relevant build cards
 * (retrieve-and-inject; the model never fetches anything itself).
 */
public final class LlmPrompts {
    private LlmPrompts() {}

    /** Fixed system line; pairs with {@link LlmActions} as the output contract. */
    public static String systemLine() {
        return "You pick ONE action for a Minecraft villager-agent. Think briefly, then answer with "
                + "one JSON object only: {\"action\":\"...\",\"args\":{...}}. No code fences, no prose.";
    }

    /**
     * Assembles the user message: the observation, the open tasks with their exact claim keys,
     * the three decision actions with their semantics, and small grounded extras (top-K pool
     * entries; build cards only when a build decision is pending).
     */
    public static String userPrompt(String observation, List<BoardTask> openTasks,
            List<String> poolSummaries, List<BuildCard> cards) {
        StringBuilder sb = new StringBuilder();
        sb.append("SITUATION:\n").append(observation);
        sb.append("\n\nOPEN TASKS:");
        if (openTasks.isEmpty()) {
            sb.append(" none — reply {\"action\":\"rest\",\"args\":{}}.");
        } else {
            for (BoardTask task : openTasks) {
                sb.append("\n- ").append(task.key()).append(" — ").append(task.detail());
            }
        }
        sb.append("\n\nDECISION — reply with exactly one JSON object:");
        sb.append("\n- Start work on an OPEN TASK: {\"action\":\"claim\",\"args\":{\"task\":\"<key>\"}} (copy the key exactly)");
        sb.append("\n- Idle: {\"action\":\"rest\",\"args\":{}}");
        sb.append("\n- Speak to a player: {\"action\":\"say\",\"args\":{\"player\":\"<name>\",\"msg\":\"<short text>\"}}");
        sb.append("\nClaiming is the only way to act: once a task is claimed, the agent's hands"
                + " (walk, place, light, build) run automatically. Any other action is ignored.");
        if (!poolSummaries.isEmpty()) {
            sb.append("\n\nPAST EXPERIENCE (what worked before):");
            for (String summary : poolSummaries) {
                sb.append("\n- ").append(summary);
            }
        }
        if (!cards.isEmpty()) {
            sb.append("\n\nBUILD LIBRARY (relevant plans):");
            for (BuildCard card : cards) {
                sb.append("\n- ").append(cardLine(card));
            }
        }
        return sb.toString();
    }

    /** One pool entry as a compact grounded line. */
    public static String poolSummaryLine(PoolEntry entry) {
        return "[" + String.join(",", entry.tags()) + "] " + entry.situationSummary()
                + " (uses=" + entry.uses() + ", successes=" + entry.successes() + ")";
    }

    /** One build card as a compact grounded line. */
    public static String cardLine(BuildCard card) {
        return card.name() + " " + card.size().getX() + "x" + card.size().getY() + "x" + card.size().getZ()
                + ": " + card.stepSummary() + " (tags: " + String.join(",", card.styleTags()) + ")";
    }
}
