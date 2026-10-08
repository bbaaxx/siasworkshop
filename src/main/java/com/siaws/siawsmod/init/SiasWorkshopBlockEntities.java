package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.village.VillageFoundationBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class SiasWorkshopBlockEntities {
    private SiasWorkshopBlockEntities() {}

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SiasWorkshop.MODID);

    public static final RegistryObject<BlockEntityType<VillageFoundationBlockEntity>> VILLAGE_FOUNDATION =
            BLOCK_ENTITY_TYPES.register("village_foundation", () -> BlockEntityType.Builder
                    .of(VillageFoundationBlockEntity::new, SiasWorkshopBlocks.VILLAGE_FOUNDATION.get()).build(null));
}
