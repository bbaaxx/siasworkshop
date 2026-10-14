package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.agents.AgentInspectorItem;
import com.siaws.siawsmod.content.village.WildernessCompassItem;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SiasWorkshopItems {
    private SiasWorkshopItems() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SiasWorkshop.MODID);

    public static final DeferredItem<BlockItem> VILLAGE_FOUNDATION_ITEM = ITEMS.registerSimpleBlockItem("village_foundation",
            SiasWorkshopBlocks.VILLAGE_FOUNDATION);

    public static final DeferredItem<Item> WILDERNESS_COMPASS = ITEMS.registerItem("wilderness_compass",
            WildernessCompassItem::new, new Item.Properties().stacksTo(1));

    /** Debug-only; gated at use time by agents.debugInspector (see Config). Not in any creative tab. */
    public static final DeferredItem<AgentInspectorItem> AGENT_INSPECTOR = ITEMS.registerItem("agent_inspector",
            AgentInspectorItem::new, new Item.Properties().stacksTo(1));
}
