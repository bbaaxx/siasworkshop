package com.siaws.siawsmod.data;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.init.SiasWorkshopBlocks;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, SiasWorkshop.MODID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        // Placeholder art borrows the vanilla iron block texture until custom textures exist
        simpleBlockWithItem(SiasWorkshopBlocks.EXAMPLE_BLOCK.get(),
                models().cubeAll("example_block", mcLoc("block/iron_block")));
    }
}
