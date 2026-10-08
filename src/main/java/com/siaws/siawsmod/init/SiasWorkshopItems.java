package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.village.WildernessCompassItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class SiasWorkshopItems {
    private SiasWorkshopItems() {}

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, SiasWorkshop.MODID);

    // Creates a new BlockItem with the id "siasworkshop:example_block"
    public static final RegistryObject<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block",
            () -> new BlockItem(SiasWorkshopBlocks.EXAMPLE_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockItem> VILLAGE_FOUNDATION_ITEM = ITEMS.register("village_foundation",
            () -> new BlockItem(SiasWorkshopBlocks.VILLAGE_FOUNDATION.get(), new Item.Properties()));

    public static final RegistryObject<Item> WILDERNESS_COMPASS = ITEMS.register("wilderness_compass",
            () -> new WildernessCompassItem(new Item.Properties().stacksTo(1)));

    // Creates a new food item with the id "siasworkshop:example_item", nutrition 1 and saturation 2
    public static final RegistryObject<Item> EXAMPLE_ITEM = ITEMS.register("example_item",
            () -> new Item(new Item.Properties().food(new FoodProperties.Builder()
                    .alwaysEat().nutrition(1).saturationMod(2f).build())));
}
