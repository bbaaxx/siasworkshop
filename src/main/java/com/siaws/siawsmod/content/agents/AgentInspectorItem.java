package com.siaws.siawsmod.content.agents;

import java.util.List;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.config.Config;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Debug tool (config-gated via {@code agents.debugInspector}): right-click a workshop agent
 * to print its observation snapshot (see {@link AgentObservation}) to the acting player's
 * chat, line by line, and in full to the server log. Not added to any creative tab; grab it
 * with {@code /give} during development.
 */
public class AgentInspectorItem extends Item {
    public AgentInspectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof WorkshopAgentEntity agent)) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide) {
            return InteractionResult.CONSUME; // server does the work; same result on both sides
        }
        if (!Config.AGENTS_DEBUG_INSPECTOR.get()) {
            player.displayClientMessage(Component.translatable("item.siasworkshop.agent_inspector.disabled"), true);
            return InteractionResult.CONSUME;
        }
        String snapshot = AgentObservation.build(agent);
        for (String line : snapshot.split("\n")) {
            player.sendSystemMessage(Component.literal(line));
        }
        SiasWorkshop.LOGGER.info("[AgentInspector] {} inspected agent {}:\n{}",
                player.getName().getString(), agent.getUUID(), snapshot);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents,
            TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.siasworkshop.agent_inspector.hint"));
    }
}
