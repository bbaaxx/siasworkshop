package com.siaws.siawsmod.data;

import java.util.concurrent.CompletableFuture;

import com.siaws.siawsmod.init.SiasWorkshopItems;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.world.item.Items;

/** Survival recipes for the two village tools; unlocked by their rarest ingredient. */
public final class ModRecipeProvider extends RecipeProvider {
    public ModRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        // Wilderness Compass: cross of bell, emerald blocks and compass around a nether star.
        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, SiasWorkshopItems.WILDERNESS_COMPASS)
                .pattern(" B ")
                .pattern("ENE")
                .pattern(" C ")
                .define('B', Items.BELL)
                .define('N', Items.NETHER_STAR)
                .define('E', Items.EMERALD_BLOCK)
                .define('C', Items.COMPASS)
                .unlockedBy("has_nether_star", has(Items.NETHER_STAR))
                .save(output);

        // Village Foundation: bell-and-lodestone core framed by shulker boxes,
        // emerald blocks and a smooth stone base.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, SiasWorkshopItems.VILLAGE_FOUNDATION_ITEM)
                .pattern("SBS")
                .pattern("ELE")
                .pattern("PPP")
                .define('S', Items.SHULKER_BOX)
                .define('B', Items.BELL)
                .define('E', Items.EMERALD_BLOCK)
                .define('L', Items.LODESTONE)
                .define('P', Items.SMOOTH_STONE)
                .unlockedBy("has_shulker_box", has(Items.SHULKER_BOX))
                .save(output);
    }
}
