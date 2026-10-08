package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.village.WildernessCompassItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class SiasWorkshopItems {
    private SiasWorkshopItems() {}

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, SiasWorkshop.MODID);

    public static final RegistryObject<BlockItem> VILLAGE_FOUNDATION_ITEM = ITEMS.register("village_foundation",
            () -> new BlockItem(SiasWorkshopBlocks.VILLAGE_FOUNDATION.get(), new Item.Properties()));

    public static final RegistryObject<Item> WILDERNESS_COMPASS = ITEMS.register("wilderness_compass",
            () -> new WildernessCompassItem(new Item.Properties().stacksTo(1)));
}
