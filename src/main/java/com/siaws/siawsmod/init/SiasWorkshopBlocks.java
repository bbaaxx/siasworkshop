package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.village.VillageFoundationBlock;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class SiasWorkshopBlocks {
    private SiasWorkshopBlocks() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, SiasWorkshop.MODID);

    public static final RegistryObject<VillageFoundationBlock> VILLAGE_FOUNDATION = BLOCKS.register("village_foundation",
            () -> new VillageFoundationBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PINK).strength(2.0F)));
}
