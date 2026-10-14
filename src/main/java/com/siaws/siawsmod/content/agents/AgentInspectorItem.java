package com.siaws.siawsmod.content.agents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.siaws.siawsmod.SiasWorkshop;
import com.siaws.siawsmod.config.Config;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 * chat, in full to the server log, and verbatim to
 * {@code debug/siasworkshop/agent-observation-<epochMillis>.txt} under the server game
 * directory (chat transcription is lossy; the file is what a prompt would receive).
 * Not added to any creative tab; grab it with {@code /give} during development.
 */
public class AgentInspectorItem extends Item {
    private static final String SNAPSHOT_DIR = "debug/siasworkshop";

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
        String snapshot = AgentObservation.build(agent, Config.AGENTS_OBSERVATION_BUDGET_TOKENS.get());
        for (String line : snapshot.split("\n")) {
            player.sendSystemMessage(Component.literal(line));
        }
        SiasWorkshop.LOGGER.info("[AgentInspector] {} inspected agent {}:\n{}",
                player.getName().getString(), agent.getUUID(), snapshot);
        saveSnapshotToFile(player, agent, snapshot);
        return InteractionResult.CONSUME;
    }

    private static void saveSnapshotToFile(Player player, WorkshopAgentEntity agent, String snapshot) {
        try {
            Path dir = ((ServerLevel) agent.level()).getServer().getFile(SNAPSHOT_DIR);
            Files.createDirectories(dir);
            Path file = dir.resolve("agent-observation-" + System.currentTimeMillis() + ".txt");
            Files.writeString(file, snapshot, StandardCharsets.UTF_8);
            player.sendSystemMessage(Component.translatable("item.siasworkshop.agent_inspector.saved",
                    SNAPSHOT_DIR + "/" + file.getFileName()));
        } catch (IOException | RuntimeException e) {
            player.sendSystemMessage(Component.translatable("item.siasworkshop.agent_inspector.save_failed",
                    String.valueOf(e.getMessage())));
            SiasWorkshop.LOGGER.warn("[AgentInspector] failed to save observation snapshot", e);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents,
            TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.siasworkshop.agent_inspector.hint"));
    }
}
