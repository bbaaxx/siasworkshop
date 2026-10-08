package com.siaws.siawsmod;

import com.siaws.siawsmod.content.village.client.VillageFoundationScreen;
import com.siaws.siawsmod.content.village.client.WildernessCompassPropertyFunction;
import com.siaws.siawsmod.init.SiasWorkshopItems;
import com.siaws.siawsmod.init.SiasWorkshopMenus;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
// Registered automatically on the mod event bus for the CLIENT dist only (Forge 47.1 has no
// dist-scoped @Mod attribute, so the client wiring lives behind @EventBusSubscriber instead).
@Mod.EventBusSubscriber(modid = SiasWorkshop.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class SiasWorkshopClient {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.register(SiasWorkshopItems.WILDERNESS_COMPASS.get(),
                    new ResourceLocation(SiasWorkshop.MODID, "angle"),
                    new WildernessCompassPropertyFunction());
            MenuScreens.register(SiasWorkshopMenus.VILLAGE_FOUNDATION.get(), VillageFoundationScreen::new);
        });
    }
}
