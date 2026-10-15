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

    public static final ModConfigSpec.BooleanValue LLM_ENABLED = BUILDER
            .comment("Enable the local LLM endpoint for agent decisions (OpenAI-compatible chat API,",
                    "e.g. LM Studio or Ollama). Offline-first: when disabled or unreachable, agents fall",
                    "back to built-in template behavior with no player-visible errors.")
            .define("llm.enabled", false);

    public static final ModConfigSpec.ConfigValue<String> LLM_ENDPOINT = BUILDER
            .comment("Base URL of the OpenAI-compatible endpoint (no trailing path). Prefer",
                    "127.0.0.1 over localhost: dev launchers set -Djava.net.preferIPv6Addresses=system,",
                    "so localhost may resolve to ::1 first and java.net.http can fail to fall back",
                    "(ClosedChannelException) — while LM Studio binds 127.0.0.1 only.")
            .define("llm.endpoint", "http://127.0.0.1:1234/v1");

    public static final ModConfigSpec.ConfigValue<String> LLM_MODEL = BUILDER
            .comment("Model id passed to the endpoint; must match a model the endpoint serves.")
            .define("llm.model", "prism-ml/bonsai-27b");

    public static final ModConfigSpec.IntValue LLM_TIMEOUT_SECONDS = BUILDER
            .comment("HTTP timeout for one LLM request; on timeout the agent falls back to templates.")
            .defineInRange("llm.timeoutSeconds", 30, 1, 120);

    public static final ModConfigSpec.IntValue LLM_MAX_TOKENS = BUILDER
            .comment("max_tokens for the decision request. Reasoning models can spend most of",
                    "the budget on hidden reasoning before emitting the final JSON, so keep",
                    "this generous; the answer itself is one small JSON object.")
            .defineInRange("llm.maxTokens", 512, 16, 512);

    public static final ModConfigSpec.DoubleValue LLM_TEMPERATURE = BUILDER
            .comment("Sampling temperature for the decision request.")
            .defineInRange("llm.temperature", 0.3, 0.0, 2.0);

    public static final ModConfigSpec.BooleanValue AGENTS_EXECUTION_LOOP_ENABLED = BUILDER
            .comment("Master toggle for the agent decision/execution loop: idle agents claim task-board",
                    "tasks (template fallback, or LLM-picked when llm.enabled) and execute them.")
            .define("agents.executionLoopEnabled", true);

    public static final ModConfigSpec.IntValue AGENTS_DECISION_INTERVAL_SECONDS = BUILDER
            .comment("How often idle agents run a decision pass (observe, retrieve, pick), in",
                    "seconds. Executing an already-claimed task runs every tick regardless.")
            .defineInRange("agents.decisionIntervalSeconds", 30, 5, 600);

    public static final ModConfigSpec.IntValue AGENTS_TASK_LEASE_TICKS = BUILDER
            .comment("Lease length an agent takes on a claimed task, in ticks (20 ticks = 1 second);",
                    "renewed while the task is being executed.")
            .defineInRange("agents.taskLeaseTicks", 200, 20, 24000);

    public static final ModConfigSpec.IntValue AGENTS_PLACE_PER_CYCLE = BUILDER
            .comment("How many blocks one agent places per execution cycle.")
            .defineInRange("agents.placePerCycle", 1, 1, 64);

    public static final ModConfigSpec.IntValue AGENTS_STATION_RADIUS = BUILDER
            .comment("How far from a build anchor the agent searches chests for build materials.")
            .defineInRange("agents.stationRadius", 16, 4, 64);

    public static final ModConfigSpec.IntValue AGENTS_GUARD_RADIUS = BUILDER
            .comment("Guardrail: agents may only place blocks within this radius of the task anchor",
                    "(the deficit player for comfort tasks, the blueprint anchor for builds). Keep this",
                    ">= agents.lightScanRadius, or light-deficit targets near the scan edge fall",
                    "outside the guard and the task can never execute.")
            .defineInRange("agents.guardRadius", 32, 4, 128);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
