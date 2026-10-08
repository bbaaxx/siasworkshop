package com.siaws.siawsmod.data;

import java.util.List;
import java.util.Set;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.data.loot.ModBlockLootTables;

import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

public final class ModDataGenerators {
    private ModDataGenerators() {}

    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();

        // Client assets: blockstates, models
        boolean client = event.includeClient();
        generator.addProvider(client, new ModBlockStateProvider(output, existingFileHelper));
        generator.addProvider(client, new ModItemModelProvider(output, existingFileHelper));

        // Server data: loot tables
        boolean server = event.includeServer();
        generator.addProvider(server, new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(ModBlockLootTables::new, LootContextParamSets.BLOCK)),
                event.getLookupProvider()));

        SiasWorkshop.LOGGER.info("Datagen providers registered for {}", SiasWorkshop.MODID);
    }
}
