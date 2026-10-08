package com.siaws.siawsmod.data.loot;

import java.util.Set;

import com.siaws.siawsmod.init.SiasWorkshopBlocks;

import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockLootTables extends BlockLootSubProvider {
    public ModBlockLootTables() {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags());
    }

    @Override
    protected void generate() {
        dropSelf(SiasWorkshopBlocks.VILLAGE_FOUNDATION.get());
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return SiasWorkshopBlocks.BLOCKS.getEntries().stream().map(RegistryObject::get)::iterator;
    }
}
