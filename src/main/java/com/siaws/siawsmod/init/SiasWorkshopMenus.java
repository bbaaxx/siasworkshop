package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.village.VillageFoundationMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class SiasWorkshopMenus {
    private SiasWorkshopMenus() {}

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, SiasWorkshop.MODID);

    public static final RegistryObject<MenuType<VillageFoundationMenu>> VILLAGE_FOUNDATION =
            MENUS.register("village_foundation", () -> IForgeMenuType.create(VillageFoundationMenu::fromNetwork));
}
