package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.village.WildernessCompassItem;

import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SiasWorkshopItems {
    private SiasWorkshopItems() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SiasWorkshop.MODID);

    // Creates a new BlockItem with the id "siasworkshop:example_block"
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block",
            SiasWorkshopBlocks.EXAMPLE_BLOCK);

    public static final DeferredItem<BlockItem> VILLAGE_FOUNDATION_ITEM = ITEMS.registerSimpleBlockItem("village_foundation",
            SiasWorkshopBlocks.VILLAGE_FOUNDATION);

    public static final DeferredItem<Item> WILDERNESS_COMPASS = ITEMS.registerItem("wilderness_compass",
            WildernessCompassItem::new, new Item.Properties().stacksTo(1));

    // Creates a new food item with the id "siasworkshop:example_item", nutrition 1 and saturation 2
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item",
            new Item.Properties().food(new FoodProperties.Builder()
                    .alwaysEdible().nutrition(1).saturationModifier(2f).build()));
}
