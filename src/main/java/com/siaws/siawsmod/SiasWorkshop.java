package com.siaws.siawsmod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.content.agents.build.BuildCards;
import com.siaws.siawsmod.content.agents.build.BuildCommand;
import com.siaws.siawsmod.content.agents.build.BlueprintLibrary;
import com.siaws.siawsmod.content.agents.AgentDecisionLoop;
import com.siaws.siawsmod.content.agents.tasks.ComfortDetectors;
import com.siaws.siawsmod.data.ModDataGenerators;
import com.siaws.siawsmod.init.SiasWorkshopBlockEntities;
import com.siaws.siawsmod.init.SiasWorkshopBlocks;
import com.siaws.siawsmod.init.SiasWorkshopCreativeTabs;
import com.siaws.siawsmod.init.SiasWorkshopDataComponents;
import com.siaws.siawsmod.init.SiasWorkshopEntities;
import com.siaws.siawsmod.init.SiasWorkshopItems;
import com.siaws.siawsmod.init.SiasWorkshopMenus;
import com.siaws.siawsmod.init.SiasWorkshopStructureProcessors;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

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
        SiasWorkshopEntities.ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(SiasWorkshopEntities::registerAttributes);

        modEventBus.addListener(ModDataGenerators::gatherData);

        NeoForge.EVENT_BUS.addListener(ComfortDetectors::onServerTick);
        NeoForge.EVENT_BUS.addListener(AgentDecisionLoop::onServerTick);
        NeoForge.EVENT_BUS.addListener(AgentDecisionLoop::onServerStopping);
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> BlueprintLibrary.INSTANCE.reload());
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(BuildCards.INSTANCE));
        NeoForge.EVENT_BUS.addListener(BuildCommand::register);

        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    }
}
