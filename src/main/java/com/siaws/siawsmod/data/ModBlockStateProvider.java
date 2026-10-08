package com.siaws.siawsmod.data;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.init.SiasWorkshopBlocks;

import net.minecraft.data.PackOutput;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, SiasWorkshop.MODID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        simpleBlockWithItem(SiasWorkshopBlocks.VILLAGE_FOUNDATION.get(),
                models().cubeAll("village_foundation", modLoc("block/village_foundation")));
    }
}
