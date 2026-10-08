package com.siaws.siawsmod.content.village.client;

import com.siaws.siawsmod.init.SiasWorkshopDataComponents;

import net.minecraft.client.renderer.item.CompassItemPropertyFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;

/**
 * Compass needle that points away from the village stored in the item's village_target component.
 * Feeds the vanilla needle math a target mirrored through the entity's own position: direction
 * to the mirror point (2E - V) is exactly "away from the village" (V), so wobble, spin and the
 * 16-sprite rendering all come from vanilla CompassItemPropertyFunction.
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
}
