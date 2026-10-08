package com.siaws.siawsmod.content.village;

import com.siaws.siawsmod.worldgen.processor.CherryfyProcessor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Manual village placement, mirroring PlaceCommand.placeStructure so block-spawned villages
 * behave exactly like the /place structure minecraft:village_plains test path. Cherry flavor
 * forces the cherryfy processor during placement via CherryfyProcessor.force.
 */
public final class VillagePlacer {
    private VillagePlacer() {}

    /** Villages structure set uses separation 8 chunks; manual placements honor the same minimum. */
    public static final int MIN_SEPARATION = 128;

    public enum Result { SUCCESS, TOO_CLOSE, FAILED }

    /**
     * Horizontal distance between two positions. Structure lookups return positions with a
     * synthetic Y (ring centers use y=32, potential spread positions y=0), so any user-facing
     * distance or separation check must ignore Y or it floors at the local terrain height.
     */
    public static int horizontalDistance(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return (int) Math.round(Math.hypot(dx, dz));
    }

    public static Result place(ServerLevel level, BlockPos pos, boolean cherry) {
        // findNearestMapStructure's radius is in SECTIONS (16 blocks), not blocks.
        BlockPos nearest = level.findNearestMapStructure(StructureTags.VILLAGE, pos,
                (MIN_SEPARATION + 15) / 16, false);
        if (nearest != null && horizontalDistance(nearest, pos) < MIN_SEPARATION) return Result.TOO_CLOSE;

        Holder.Reference<Structure> plains = level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getHolderOrThrow(ResourceKey.create(Registries.STRUCTURE,
                        new ResourceLocation("village_plains")));
        ChunkGenerator generator = level.getChunkSource().getGenerator();

        CherryfyProcessor.force(cherry);
        try {
            // 1.20.1 signature: StructureTemplateManager + explicit LevelHeightAccessor.
            StructureStart start = plains.value().generate(
                    level.registryAccess(), generator, generator.getBiomeSource(),
                    level.getChunkSource().randomState(), level.getStructureManager(),
                    level.getSeed(), new ChunkPos(pos), 0, level, biome -> true);
            if (!start.isValid()) return Result.FAILED;

            BoundingBox box = start.getBoundingBox();
            ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
            ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));
            ChunkPos.rangeClosed(min, max).forEach(chunkPos -> {
                level.getChunk(chunkPos.x, chunkPos.z); // PlaceCommand requires all bbox chunks loaded
                start.placeInChunk(level, level.structureManager(), generator, level.getRandom(),
                        new BoundingBox(chunkPos.getMinBlockX(), level.getMinBuildHeight(), chunkPos.getMinBlockZ(),
                                chunkPos.getMaxBlockX(), level.getMaxBuildHeight(), chunkPos.getMaxBlockZ()),
                        chunkPos);
            });
            // placeInChunk stamps pieces but never registers the start — without this the village
            // stays invisible to separation checks and /locate (same blind spot as /place structure).
            ChunkAccess startChunk = level.getChunk(start.getChunkPos().x, start.getChunkPos().z,
                    ChunkStatus.STRUCTURE_STARTS);
            level.structureManager().setStartForStructure(SectionPos.of(start.getChunkPos(), 0),
                    start.getStructure(), start, startChunk);
            return Result.SUCCESS;
        } finally {
            CherryfyProcessor.force(false);
        }
    }
}
