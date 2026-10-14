package com.siaws.siawsmod.init;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SiasWorkshopEntities {
    private SiasWorkshopEntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, SiasWorkshop.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<WorkshopAgentEntity>> WORKSHOP_AGENT =
            ENTITY_TYPES.register("workshop_agent", () -> EntityType.Builder.of(WorkshopAgentEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(8)
                    .build("workshop_agent"));

    /** Wired to the mod event bus from the entrypoint. No spawn egg — debug-spawn via /summon. */
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(WORKSHOP_AGENT.get(), WorkshopAgentEntity.createAttributes().build());
    }
}
