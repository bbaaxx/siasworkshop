package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SiasWorkshopDataComponents {
    private SiasWorkshopDataComponents() {}

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, SiasWorkshop.MODID);

    /** Position of the nearest village at last scan, stored by the wilderness compass. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> VILLAGE_TARGET =
            DATA_COMPONENT_TYPES.register("village_target", () -> DataComponentType.<BlockPos>builder()
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC)
                    .build());
}
