package com.siaws.siawsmod;

import com.siaws.siawsmod.content.village.client.VillageFoundationScreen;
import com.siaws.siawsmod.content.village.client.WildernessCompassPropertyFunction;
import com.siaws.siawsmod.init.SiasWorkshopItems;
import com.siaws.siawsmod.init.SiasWorkshopMenus;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = SiasWorkshop.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = SiasWorkshop.MODID, value = Dist.CLIENT)
public class SiasWorkshopClient {
    public SiasWorkshopClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(SiasWorkshopItems.WILDERNESS_COMPASS.get(),
                ResourceLocation.fromNamespaceAndPath(SiasWorkshop.MODID, "angle"),
                new WildernessCompassPropertyFunction()));
    }

    @SubscribeEvent
    static void onMenuScreens(RegisterMenuScreensEvent event) {
        event.register(SiasWorkshopMenus.VILLAGE_FOUNDATION.get(), VillageFoundationScreen::new);
    }
}
