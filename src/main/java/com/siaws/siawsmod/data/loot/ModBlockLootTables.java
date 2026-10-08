package com.siaws.siawsmod.data.loot;

import java.util.Set;

import com.siaws.siawsmod.init.SiasWorkshopBlocks;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;

public class ModBlockLootTables extends BlockLootSubProvider {
    public ModBlockLootTables(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        dropSelf(SiasWorkshopBlocks.EXAMPLE_BLOCK.get());
        dropSelf(SiasWorkshopBlocks.VILLAGE_FOUNDATION.get());
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return SiasWorkshopBlocks.BLOCKS.getEntries().stream().map(Holder::value)::iterator;
    }
}
