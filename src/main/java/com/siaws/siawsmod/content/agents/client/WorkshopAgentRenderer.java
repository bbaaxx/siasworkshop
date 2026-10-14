package com.siaws.siawsmod.content.agents.client;

import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Humanoid agent using vanilla rendering: the classic zombie humanoid layer + a placeholder
 * texture. Until final art lands (docs/art/texture-specs.md), we reuse the vanilla zombie
 * texture so the agent reads as a humanoid without shipping stub PNGs; replace TEXTURE with
 * {@code siasworkshop:textures/entity/workshop_agent.png} when the art is delivered.
 */
public class WorkshopAgentRenderer extends HumanoidMobRenderer<WorkshopAgentEntity, HumanoidModel<WorkshopAgentEntity>> {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public WorkshopAgentRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(WorkshopAgentEntity entity) {
        return TEXTURE;
    }
}
