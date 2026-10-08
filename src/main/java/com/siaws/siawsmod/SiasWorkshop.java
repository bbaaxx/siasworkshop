package com.siaws.siawsmod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.data.ModDataGenerators;
import com.siaws.siawsmod.init.SiasWorkshopBlockEntities;
import com.siaws.siawsmod.init.SiasWorkshopBlocks;
import com.siaws.siawsmod.init.SiasWorkshopCreativeTabs;
import com.siaws.siawsmod.init.SiasWorkshopItems;
import com.siaws.siawsmod.init.SiasWorkshopMenus;
import com.siaws.siawsmod.init.SiasWorkshopStructureProcessors;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(SiasWorkshop.MODID)
public class SiasWorkshop {
    public static final String MODID = "siasworkshop";
    public static final Logger LOGGER = LogUtils.getLogger();

    // No-arg constructor: Forge 47.4.x does not inject (IEventBus, ModContainer) the way
    // NeoForge 47.1.x does — FMLJavaModLoadingContext.get().getModEventBus() works on both
    // halves of the 47.1-compatible line.
    public SiasWorkshop() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        SiasWorkshopBlocks.BLOCKS.register(modEventBus);
        SiasWorkshopItems.ITEMS.register(modEventBus);
        SiasWorkshopCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        SiasWorkshopStructureProcessors.STRUCTURE_PROCESSORS.register(modEventBus);
        SiasWorkshopBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        SiasWorkshopMenus.MENUS.register(modEventBus);

        modEventBus.addListener(ModDataGenerators::gatherData);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    }
}
