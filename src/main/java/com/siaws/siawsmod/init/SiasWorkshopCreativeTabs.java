package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SiasWorkshopCreativeTabs {
    private SiasWorkshopCreativeTabs() {}

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB,
            SiasWorkshop.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SIASWORKSHOP_TAB = CREATIVE_MODE_TABS.register("siasworkshop",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.siasworkshop"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> SiasWorkshopItems.WILDERNESS_COMPASS.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(SiasWorkshopItems.WILDERNESS_COMPASS.get());
                        output.accept(SiasWorkshopItems.VILLAGE_FOUNDATION_ITEM.get());
                    })
                    .build());
}
