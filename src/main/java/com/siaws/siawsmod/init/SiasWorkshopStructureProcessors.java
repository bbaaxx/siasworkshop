package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.worldgen.processor.CherryfyProcessor;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class SiasWorkshopStructureProcessors {
    private SiasWorkshopStructureProcessors() {}

    public static final DeferredRegister<StructureProcessorType<?>> STRUCTURE_PROCESSORS = DeferredRegister
            .create(Registries.STRUCTURE_PROCESSOR, SiasWorkshop.MODID);

    public static final RegistryObject<StructureProcessorType<CherryfyProcessor>> CHERRYFY = STRUCTURE_PROCESSORS
            .register("cherryfy", CherryfyProcessor::type);
}
