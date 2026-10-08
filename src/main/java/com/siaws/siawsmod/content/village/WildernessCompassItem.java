package com.siaws.siawsmod.content.village;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Scans for the nearest village on use and stores its position; the client needle then points
 * directly away from it (see WildernessCompassPropertyFunction). Creative-only for now.
 */
public class WildernessCompassItem extends Item {
    /** Scan radius in blocks; vanilla village spacing is 34 chunks, so 640 covers the gap. */
    public static final int SCAN_RADIUS = 640;

    /** Share-NBT key holding the scanned village position (BlockPos packed via {@link BlockPos#asLong()}). */
    public static final String TARGET_TAG = "village_target";

    public WildernessCompassItem(Properties properties) {
        super(properties);
    }

    /** Village position stored on the stack, or null if the compass hasn't scanned one in. */
    public static BlockPos getTarget(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TARGET_TAG)) return null;
        return BlockPos.of(tag.getLong(TARGET_TAG));
    }

    public static void setTarget(ItemStack stack, BlockPos pos) {
        stack.getOrCreateTag().putLong(TARGET_TAG, pos.asLong());
    }

    public static void clearTarget(ItemStack stack) {
        stack.removeTagKey(TARGET_TAG);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);

        // findNearestMapStructure's radius is in SECTIONS (16 blocks), not blocks.
        BlockPos found = ((ServerLevel) level).findNearestMapStructure(StructureTags.VILLAGE,
                player.blockPosition(), SCAN_RADIUS / 16, false);
        if (found == null) {
            clearTarget(stack);
            player.displayClientMessage(Component.translatable(
                    "item.siasworkshop.wilderness_compass.no_village", SCAN_RADIUS), true);
        } else {
            int distance = VillagePlacer.horizontalDistance(found, player.blockPosition());
            // Store with the player's Y: the mirror-target needle math then points purely
            // horizontally away, independent of the synthetic Y in lookup results.
            setTarget(stack, new BlockPos(found.getX(), player.getBlockY(), found.getZ()));
            Component verdict = distance >= VillagePlacer.MIN_SEPARATION
                    ? Component.translatable("item.siasworkshop.wilderness_compass.clear")
                    : Component.translatable("item.siasworkshop.wilderness_compass.near", VillagePlacer.MIN_SEPARATION);
            player.displayClientMessage(Component.translatable(
                            "item.siasworkshop.wilderness_compass.scanned", distance)
                    .append(" — ").append(verdict), true);
        }
        player.getCooldowns().addCooldown(this, 20);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltipComponents,
            TooltipFlag tooltipFlag) {
        BlockPos target = getTarget(stack);
        if (target != null) {
            tooltipComponents.add(Component.translatable(
                    "item.siasworkshop.wilderness_compass.tracking", target.getX(), target.getZ()));
        }
    }
}
