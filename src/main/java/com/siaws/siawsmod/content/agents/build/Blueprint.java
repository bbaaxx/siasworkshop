package com.siaws.siawsmod.content.agents.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * A blueprint: a validated build plan an agent can execute block by block (design pillar 4 —
 * builders never build randomly; every build task references a concrete blueprint). Produced
 * by our own parsers ({@link SchemParser}, {@link StructureNbtParser}) from player-imported
 * files; we never copy WorldEdit/Litematica code, only interpret the public formats.
 *
 * @param name       unique name, usually the import file name without extension
 * @param size       bounding box of the structure (width, height, length)
 * @param placements block id + position relative to the anchor; never empty in a valid blueprint
 */
public record Blueprint(String name, Vec3i size, List<Placement> placements) {

    /** One block to place: a registry-style id (e.g. {@code minecraft:stone}) plus an anchor-relative position. */
    public record Placement(String blockId, BlockPos rel) {}

    public Blueprint {
        placements = List.copyOf(placements);
    }

    /**
     * Deterministic build order: bottom-up first, then proximity to the anchor, then a stable
     * tie-break. Two runs over the same blueprint always produce the same sequence.
     */
    public List<Placement> placementsSortedForBuild() {
        List<Placement> sorted = new ArrayList<>(placements);
        sorted.sort(Comparator
                .comparingInt((Placement placement) -> placement.rel().getY())
                .thenComparingInt(placement -> Math.abs(placement.rel().getX())
                        + Math.abs(placement.rel().getY()) + Math.abs(placement.rel().getZ()))
                .thenComparingInt(placement -> placement.rel().getX())
                .thenComparingInt(placement -> placement.rel().getZ())
                .thenComparing(Placement::blockId));
        return sorted;
    }
}
