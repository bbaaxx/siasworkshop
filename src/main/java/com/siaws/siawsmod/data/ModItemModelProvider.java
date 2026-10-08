package com.siaws.siawsmod.data;

import com.siaws.siawsmod.SiasWorkshop;

import net.minecraft.data.PackOutput;
import net.minecraftforge.client.model.generators.ItemModelProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

public class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, SiasWorkshop.MODID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        // Placeholder art borrows the vanilla apple texture until custom textures exist
        withExistingParent("example_item", mcLoc("item/generated"))
                .texture("layer0", mcLoc("item/apple"));
    }
}
