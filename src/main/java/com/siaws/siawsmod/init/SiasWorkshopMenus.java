package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.village.VillageFoundationMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SiasWorkshopMenus {
    private SiasWorkshopMenus() {}

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, SiasWorkshop.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<VillageFoundationMenu>> VILLAGE_FOUNDATION =
            MENUS.register("village_foundation", () -> IMenuTypeExtension.create(VillageFoundationMenu::fromNetwork));
}
