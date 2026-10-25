package com.siaws.siawsmod.content.agents.primitives;

import java.util.List;

import javax.annotation.Nullable;

import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

/**
 * The break primitive (action-surface expansion, slice 1): the first agent capability that
 * CHANGES the world by removal, designed as a composable building block for the model's
 * means-ends reasoning (user direction 2026-10-23 — the LLM decides what must be broken
 * toward a goal; the mod validates and executes). Guardrails mirror the placement side:
 * agent-relative guard radius, vanilla tool-tier rules (NEEDS_*_TOOL blocks refuse without
 * the right tiered tool, with a teaching reason), protected structures (containers, beds,
 * any block entity) never break, unbreakable blocks refuse, and drops land in the agent's
 * inventory with the tool taking durability. If a suitable tool sits anywhere in the
 * inventory the primitive equips it — using the right tool is mechanics, not planning.
 *
 * <p>Also consumed deterministically by the BUILD executor to clear blueprint obstructions
 * (the live 2026-10-20 leaves episode: obstruction used to be a terminal-fail after 20
 * cycles; now it is one break away when tooling allows).
 */
public final class BreakSupport {

    /** One guardrail decision; {@code reason} is a short teaching token for the model. */
    public record BreakVerdict(boolean allowed, @Nullable String reason) {
        static final BreakVerdict OK = new BreakVerdict(true, null);
    }

    private BreakSupport() {}

    /** True while {@code pos} may be broken by {@code agent} right now (all guardrails). */
    public static boolean canBreak(ServerLevel level, WorkshopAgentEntity agent, BlockPos pos) {
        return evaluate(level, agent, pos).allowed();
    }

    /**
     * Full guardrail evaluation. Reasons: {@code breaking_disabled}, {@code no_block},
     * {@code unbreakable}, {@code too_far}, {@code protected}, and
     * {@code needs_tool:<tier>_<kind>} (e.g. {@code needs_tool:stone_pickaxe}) — the
     * model reads the reason to plan the means (craft/withdraw the tool, then retry).
     */
    public static BreakVerdict evaluate(ServerLevel level, WorkshopAgentEntity agent, BlockPos pos) {
        if (!Config.AGENTS_BREAKING_ENABLED.get()) {
            return new BreakVerdict(false, "breaking_disabled");
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return new BreakVerdict(false, "no_block");
        }
        if (state.getDestroySpeed(level, pos) < 0.0F) {
            return new BreakVerdict(false, "unbreakable");
        }
        double guard = Config.AGENTS_GUARD_RADIUS.get();
        if (agent.blockPosition().distSqr(pos) > guard * guard) {
            return new BreakVerdict(false, "too_far");
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null || state.getBlock() instanceof BedBlock) {
            return new BreakVerdict(false, "protected");
        }
        int requiredTier = requiredTier(state);
        if (requiredTier > 0) {
            if (!heldIsCorrect(agent, state)) {
                int slot = findToolSlot(agent, state);
                if (slot < 0) {
                    return new BreakVerdict(false,
                            "needs_tool:" + tierName(requiredTier) + "_" + toolKind(state));
                }
                equip(agent, slot);
            }
        }
        return BreakVerdict.OK;
    }

    /**
     * Breaks {@code pos} after re-validating, vanilla-survival style: drops computed from
     * the block (no player loot context), land in the agent's inventory (overflow dropped
     * at its feet), the used tool loses one durability, silk touch is honored. One call =
     * one finished break — multi-tick digging progress is a mining-slice refinement.
     */
    public static boolean breakBlock(ServerLevel level, WorkshopAgentEntity agent, BlockPos pos) {
        if (!canBreak(level, agent, pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        BlockEntity entity = level.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDrops(state, level, pos, entity);
        level.removeBlockEntity(pos);
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        for (ItemStack drop : drops) {
            if (!agent.getInventory().addItem(drop).isEmpty()) {
                Containers.dropItemStack(level, agent.getX(), agent.getY() + 0.5, agent.getZ(), drop);
            }
        }
        ItemStack held = agent.getMainHandItem();
        if (held.isCorrectToolForDrops(state)) {
            int damage = held.getDamageValue() + 1;
            if (damage >= held.getMaxDamage()) {
                agent.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            } else {
                held.setDamageValue(damage);
            }
        }
        return true;
    }

    /** Vanilla tier requirement from the NEEDS_*_TOOL tags; 0 = hand-breakable for drops. */
    public static int requiredTier(BlockState state) {
        int required = 0;
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            required = 3;
        } else if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            required = 2;
        } else if (state.is(BlockTags.NEEDS_STONE_TOOL)) {
            required = 1;
        }
        return required;
    }

    /** Which tool category the block wants (pickaxe for all NEEDS_* blocks in vanilla). */
    private static String toolKind(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return "axe";
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return "shovel";
        }
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            return "hoe";
        }
        return "pickaxe";
    }

    /** Vanilla drop correctness lives in the Tool data component (1.20.5+); no numeric tiers. */
    private static boolean isCorrectTool(ItemStack stack, BlockState state) {
        Tool tool = stack.get(DataComponents.TOOL);
        return tool != null && tool.isCorrectForDrops(state);
    }

    private static boolean heldIsCorrect(WorkshopAgentEntity agent, BlockState state) {
        return isCorrectTool(agent.getMainHandItem(), state);
    }

    private static int findToolSlot(WorkshopAgentEntity agent, BlockState state) {
        for (int slot = 0; slot < agent.getInventory().getContainerSize(); slot++) {
            if (isCorrectTool(agent.getInventory().getItem(slot), state)) {
                return slot;
            }
        }
        return -1;
    }

    /** Swaps the tool at {@code slot} into the main hand, stashing whatever was held. */
    private static void equip(WorkshopAgentEntity agent, int slot) {
        ItemStack tool = agent.getInventory().getItem(slot);
        ItemStack held = agent.getMainHandItem();
        agent.getInventory().setItem(slot, held);
        agent.setItemSlot(EquipmentSlot.MAINHAND, tool);
    }

    private static String tierName(int tier) {
        return switch (tier) {
            case 1 -> "stone";
            case 2 -> "iron";
            case 3 -> "diamond";
            case 4 -> "netherite";
            default -> "wooden";
        };
    }
}
