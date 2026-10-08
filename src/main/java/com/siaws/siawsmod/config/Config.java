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

    public static final ModConfigSpec SPEC = BUILDER.build();
}
