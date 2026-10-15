package com.siaws.siawsmod.content.llm;

import java.util.List;

import com.siaws.siawsmod.content.agents.build.BuildCard;
import com.siaws.siawsmod.content.agents.pool.PoolEntry;

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
        return "You pick ONE action for a Minecraft villager-agent. Answer with one JSON object only: "
                + "{\"action\":\"...\",\"args\":{...}}. No code fences, no prose.";
    }

    /**
     * Assembles the user message: observation text, the action space, pool summaries, and
     * (optionally) build cards. Each extra is capped small so the prompt stays short.
     */
    public static String userPrompt(String observation, List<String> poolSummaries, List<BuildCard> cards) {
        StringBuilder sb = new StringBuilder();
        sb.append("SITUATION:\n").append(observation);
        sb.append("\n\nACTIONS: ").append(String.join(", ", LlmActions.validActions()));
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
