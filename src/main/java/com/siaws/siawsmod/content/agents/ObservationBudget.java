package com.siaws.siawsmod.content.agents;

/**
 * Token-budget estimator for the agent observation snapshot (design pillar 2, "Measured
 * budget"): one token ≈ four characters of the plain-text snapshot. Deliberately crude —
 * it only needs to rank compression levels consistently, not match a tokenizer.
 */
public final class ObservationBudget {
    private ObservationBudget() {}

    /** Rough token estimate: ceil(char count / 4). */
    public static int estimateTokens(String text) {
        return (text.length() + 3) / 4;
    }
}
