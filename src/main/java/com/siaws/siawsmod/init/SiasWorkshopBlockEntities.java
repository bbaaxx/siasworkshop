package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.village.VillageFoundationBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SiasWorkshopBlockEntities {
    private SiasWorkshopBlockEntities() {}

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SiasWorkshop.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VillageFoundationBlockEntity>> VILLAGE_FOUNDATION =
            BLOCK_ENTITY_TYPES.register("village_foundation", () -> BlockEntityType.Builder
                    .of(VillageFoundationBlockEntity::new, SiasWorkshopBlocks.VILLAGE_FOUNDATION.get()).build(null));
}
