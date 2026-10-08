package com.siaws.siawsmod.data;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.init.SiasWorkshopItems;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, SiasWorkshop.MODID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        // The wilderness compass model is hand-written under src/main/resources (32 angle-override frames).
    }
}
