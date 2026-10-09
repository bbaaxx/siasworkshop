package com.siaws.siawsmod.content.village.client;

import com.siaws.siawsmod.init.SiasWorkshopDataComponents;

import javax.annotation.Nullable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.CompassItemPropertyFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Compass needle that points away from the village stored in the item's village_target component.
 * Feeds the vanilla needle math a target mirrored through the entity's own position: direction
 * to the mirror point (2E - V) is exactly "away from the village" (V), so wobble and the
 * rotating frames come from vanilla CompassItemPropertyFunction. Before the first scan (no
 * village_target) vanilla would spin the needle randomly; we rest at the up frame instead.
 */
public class WildernessCompassPropertyFunction extends CompassItemPropertyFunction {
    public WildernessCompassPropertyFunction() {
        super((level, stack, entity) -> {
            BlockPos village = stack.get(SiasWorkshopDataComponents.VILLAGE_TARGET.get());
            if (village == null) return null;
            double x = 2 * entity.getX() - village.getX();
            double y = 2 * entity.getY() - village.getY();
            double z = 2 * entity.getZ() - village.getZ();
            return new GlobalPos(entity.level().dimension(), BlockPos.containing(x, y, z));
        });
    }

    @Override
    public float unclampedCall(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
        if (stack.get(SiasWorkshopDataComponents.VILLAGE_TARGET.get()) == null) {
            return 0.0F;
        }
        return super.unclampedCall(stack, level, entity, seed);
    }
}
