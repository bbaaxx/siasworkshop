package com.siaws.siawsmod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config: per-world (each save gets its own copy under serverconfig/), editable
 * from the in-game mods screen. Registered as ModConfig.Type.SERVER in the entrypoint.
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue CHERRYFY_VILLAGES = BUILDER
            .comment("Re-skin generated villages with cherry-blossom materials: the village's primary wood",
                    "becomes cherry and accent woods are re-assigned per building (birch/jungle/acacia).",
                    "Only affects newly generated chunks; existing chunks are never retrofitted.")
            .define("cherryfyVillages", false);

    public static final ModConfigSpec.BooleanValue AGENTS_DEBUG_INSPECTOR = BUILDER
            .comment("Enable the Agent Inspector debug item: right-click a workshop agent to print its",
                    "observation snapshot to the acting player's chat and the server log. Debug tooling only.")
            .define("agents.debugInspector", true);

    public static final ModConfigSpec.BooleanValue AGENTS_COMFORT_DETECTORS_ENABLED = BUILDER
            .comment("Run the comfort deficit detectors (light, shelter) on the server tick: they raise",
                    "shared task-board entries when a player sits in the dark or spends the night exposed.",
                    "Deterministic — the detectors decide THAT a fix is needed, agents decide HOW.")
            .define("agents.comfortDetectorsEnabled", true);

    public static final ModConfigSpec.IntValue AGENTS_DETECTOR_INTERVAL_TICKS = BUILDER
            .comment("How often the comfort detectors scan, in server ticks (20 ticks = 1 second).")
            .defineInRange("agents.detectorIntervalTicks", 40, 1, 1200);

    public static final ModConfigSpec.IntValue AGENTS_LIGHT_SCAN_RADIUS = BUILDER
            .comment("Cube radius scanned per player by the light detector: positions that are air, have a",
                    "solid block below, and block light below the threshold count as spawnable-dark spots.")
            .defineInRange("agents.lightScanRadius", 8, 2, 16);

    public static final ModConfigSpec.IntValue AGENTS_LIGHT_THRESHOLD = BUILDER
            .comment("Block-light level below which an air position with a solid floor counts as dark.")
            .defineInRange("agents.lightThreshold", 4, 0, 15);

    public static final ModConfigSpec.IntValue AGENTS_SHELTER_RADIUS = BUILDER
            .comment("Cube radius searched around each player for a bed when the shelter detector runs at night.")
            .defineInRange("agents.shelterRadius", 16, 4, 32);

    public static final ModConfigSpec.IntValue AGENTS_POOL_MAX_ENTRIES = BUILDER
            .comment("Cap on the shared decision pool: when a new entry would exceed it, the least recently",
                    "used entry is evicted (entries never retrieved go first).")
            .defineInRange("agents.poolMaxEntries", 256, 1, 4096);

    public static final ModConfigSpec.IntValue AGENTS_POOL_TOP_K = BUILDER
            .comment("How many pool entries an agent retrieves at a decision point (K for top-K tag match).")
            .defineInRange("agents.poolTopK", 3, 1, 16);

    public static final ModConfigSpec.IntValue AGENTS_POOL_TRUST_THRESHOLD = BUILDER
            .comment("A pool entry becomes trusted (ranked first in retrieval) after this many recorded successes.")
            .defineInRange("agents.poolTrustThreshold", 3, 1, 100);

    public static final ModConfigSpec.IntValue AGENTS_OBSERVATION_BUDGET_TOKENS = BUILDER
            .comment("Token budget for the agent observation snapshot (~4 chars per token); sections shrink",
                    "bottom-up in salience order until the estimate fits. SELF and AVAILABLE ACTIONS always survive.")
            .defineInRange("agents.observationBudgetTokens", 900, 100, 10000);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
