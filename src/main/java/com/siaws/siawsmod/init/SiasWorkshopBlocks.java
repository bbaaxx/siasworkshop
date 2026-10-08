package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.village.VillageFoundationBlock;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SiasWorkshopBlocks {
    private SiasWorkshopBlocks() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SiasWorkshop.MODID);

    // Creates a new Block with the id "siasworkshop:example_block", combining the namespace and path
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block",
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE));

    public static final DeferredBlock<VillageFoundationBlock> VILLAGE_FOUNDATION = BLOCKS.register("village_foundation",
            () -> new VillageFoundationBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PINK).strength(2.0F)));
}
