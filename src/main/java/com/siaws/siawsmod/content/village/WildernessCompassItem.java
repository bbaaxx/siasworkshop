package com.siaws.siawsmod.content.village;

import java.util.List;

import com.siaws.siawsmod.init.SiasWorkshopDataComponents;

import net.minecraft.core.BlockPos;
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

    public WildernessCompassItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);

        // findNearestMapStructure's radius is in SECTIONS (16 blocks), not blocks.
        BlockPos found = ((ServerLevel) level).findNearestMapStructure(StructureTags.VILLAGE,
                player.blockPosition(), SCAN_RADIUS / 16, false);
        if (found == null) {
            stack.remove(SiasWorkshopDataComponents.VILLAGE_TARGET.get());
            player.displayClientMessage(Component.translatable(
                    "item.siasworkshop.wilderness_compass.no_village", SCAN_RADIUS), true);
        } else {
            int distance = VillagePlacer.horizontalDistance(found, player.blockPosition());
            // Store with the player's Y: the mirror-target needle math then points purely
            // horizontally away, independent of the synthetic Y in lookup results.
            stack.set(SiasWorkshopDataComponents.VILLAGE_TARGET.get(),
                    new BlockPos(found.getX(), player.getBlockY(), found.getZ()));
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
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents,
            TooltipFlag tooltipFlag) {
        BlockPos target = stack.get(SiasWorkshopDataComponents.VILLAGE_TARGET.get());
        if (target != null) {
            tooltipComponents.add(Component.translatable(
                    "item.siasworkshop.wilderness_compass.tracking", target.getX(), target.getZ()));
        }
    }
}
