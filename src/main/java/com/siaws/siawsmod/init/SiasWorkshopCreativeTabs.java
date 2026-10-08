package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SiasWorkshopCreativeTabs {
    private SiasWorkshopCreativeTabs() {}

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB,
            SiasWorkshop.MODID);

    // Creates a creative tab with the id "siasworkshop:example_tab" for the example item, placed after the combat tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.siasworkshop")) // The language key for the tab title
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> SiasWorkshopItems.EXAMPLE_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(SiasWorkshopItems.EXAMPLE_ITEM.get());
                        output.accept(SiasWorkshopItems.EXAMPLE_BLOCK_ITEM.get());
                    })
                    .build());

    // Adds the example block item to the vanilla building blocks tab
    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(SiasWorkshopItems.EXAMPLE_BLOCK_ITEM);
        }
    }
}
