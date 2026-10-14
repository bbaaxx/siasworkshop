package com.siaws.siawsmod.content.agents;

import com.siaws.siawsmod.SiasWorkshop;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/**
 * Stub for the hybrid embodiment seam (design pillar 1, option C): the agent's body is a
 * cheap {@link net.minecraft.world.entity.PathfinderMob}, while a NeoForge {@code FakePlayer}
 * delegate will later <em>perform</em> player-gated interactions on the agent's behalf —
 * breaking, placing, and using blocks/items. Keeping the seam as its own type means the
 * goals and the observation code never touch FakePlayer bookkeeping directly.
 *
 * <p><b>Not wired yet.</b> When implemented (future spike):
 * <ul>
 *   <li>one delegate per agent, created lazily on the server thread via
 *       {@code FakePlayerFactory.getMinecraft(ServerLevel)};</li>
 *   <li>delegate position/look/held item synced from the entity before each action;</li>
 *   <li>every performed action validated against real world state and logged (grief guardrails).</li>
 * </ul>
 */
public final class AgentInteractionDelegate {
    private final WorkshopAgentEntity agent;

    public AgentInteractionDelegate(WorkshopAgentEntity agent) {
        this.agent = agent;
    }

    /** Future: break {@code pos} with the agent's held tool, via the FakePlayer delegate. */
    public InteractionResult breakBlock(BlockPos pos) {
        return notImplemented("breakBlock", pos);
    }

    /** Future: place the agent's held block at {@code pos}. */
    public InteractionResult placeBlock(BlockPos pos) {
        return notImplemented("placeBlock", pos);
    }

    /** Future: use the held item (or on the block at {@code pos}) as a player would. */
    public InteractionResult use(ItemStack stack) {
        return notImplemented("use", stack.getItem());
    }

    private InteractionResult notImplemented(String action, Object target) {
        SiasWorkshop.LOGGER.debug("AgentInteractionDelegate.{} not implemented yet (agent {}, target {})",
                action, agent.getUUID(), target);
        return InteractionResult.FAIL;
    }
}
