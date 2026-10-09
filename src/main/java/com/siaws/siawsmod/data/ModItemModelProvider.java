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
        // Keep the angle-override table hand-written; generate each referenced frame model.
        for (int frame = 0; frame < 32; frame++) {
            String name = "wilderness_compass_%02d".formatted(frame);
            withExistingParent(name, mcLoc("item/generated"))
                    .texture("layer0", modLoc("item/" + name));
        }
    }
}
