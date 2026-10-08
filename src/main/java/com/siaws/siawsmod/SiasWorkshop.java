package com.siaws.siawsmod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.data.ModDataGenerators;
import com.siaws.siawsmod.init.SiasWorkshopBlocks;
import com.siaws.siawsmod.init.SiasWorkshopCreativeTabs;
import com.siaws.siawsmod.init.SiasWorkshopItems;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(SiasWorkshop.MODID)
public class SiasWorkshop {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "siasworkshop";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public SiasWorkshop(IEventBus modEventBus, ModContainer modContainer) {
        // Register the Deferred Registers from the init package so blocks, items, and tabs get registered
        SiasWorkshopBlocks.BLOCKS.register(modEventBus);
        SiasWorkshopItems.ITEMS.register(modEventBus);
        SiasWorkshopCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (SiasWorkshop) to respond directly to
        // events. Do not add this line if there are no @SubscribeEvent-annotated functions in this class.
        NeoForge.EVENT_BUS.register(this);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(SiasWorkshopCreativeTabs::addCreative);
        modEventBus.addListener(ModDataGenerators::gatherData);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
