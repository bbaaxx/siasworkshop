package com.siaws.siawsmod.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server config: per-world (each save gets its own copy under serverconfig/), editable
 * from the in-game mods screen. Registered as ModConfig.Type.SERVER in the entrypoint.
 */
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue CHERRYFY_VILLAGES = BUILDER
            .comment("Re-skin generated villages with cherry-blossom materials: the village's primary wood",
                    "becomes cherry and accent woods are re-assigned per building (birch/jungle/acacia).",
                    "Only affects newly generated chunks; existing chunks are never retrofitted.")
            .define("cherryfyVillages", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();
}
