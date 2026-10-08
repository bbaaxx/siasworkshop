package com.siaws.siawsmod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.data.ModDataGenerators;
import com.siaws.siawsmod.init.SiasWorkshopBlockEntities;
import com.siaws.siawsmod.init.SiasWorkshopBlocks;
import com.siaws.siawsmod.init.SiasWorkshopCreativeTabs;
import com.siaws.siawsmod.init.SiasWorkshopDataComponents;
import com.siaws.siawsmod.init.SiasWorkshopItems;
import com.siaws.siawsmod.init.SiasWorkshopMenus;
import com.siaws.siawsmod.init.SiasWorkshopStructureProcessors;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(SiasWorkshop.MODID)
public class SiasWorkshop {
    public static final String MODID = "siasworkshop";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SiasWorkshop(IEventBus modEventBus, ModContainer modContainer) {
        SiasWorkshopBlocks.BLOCKS.register(modEventBus);
        SiasWorkshopItems.ITEMS.register(modEventBus);
        SiasWorkshopCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        SiasWorkshopStructureProcessors.STRUCTURE_PROCESSORS.register(modEventBus);
        SiasWorkshopDataComponents.DATA_COMPONENT_TYPES.register(modEventBus);
        SiasWorkshopBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        SiasWorkshopMenus.MENUS.register(modEventBus);

        modEventBus.addListener(ModDataGenerators::gatherData);

        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    }
}
