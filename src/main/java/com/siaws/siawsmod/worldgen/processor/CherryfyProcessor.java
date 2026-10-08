package com.siaws.siawsmod.worldgen.processor;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.init.SiasWorkshopStructureProcessors;
import com.siaws.siawsmod.worldgen.wood.WoodFamily;
import com.siaws.siawsmod.worldgen.wood.WoodFamily.Family;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/**
 * Re-skins village pieces: the primary wood family (default oak) becomes the base
 * family (default cherry); any secondary/accent wood is re-assigned per structure
 * piece, drawn at random from the configured accent candidates. Parametric via codec:
 * primary, base, sources, accents are all JSON-configurable for palette experiments.
 * Gated by the server config {@code cherryfyVillages} (default off): when disabled the
 * processor is a pass-through, so villages generate exactly as vanilla.
 */
public class CherryfyProcessor extends StructureProcessor {
    public static final MapCodec<CherryfyProcessor> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.optionalFieldOf("primary", "oak").forGetter(p -> p.primaryId),
            Codec.STRING.optionalFieldOf("base", "cherry").forGetter(p -> p.baseId),
            Codec.STRING.listOf().optionalFieldOf("sources", List.of("birch", "jungle", "acacia", "spruce", "dark_oak"))
                    .forGetter(p -> p.sourceIds),
            Codec.STRING.listOf().optionalFieldOf("accents", List.of("birch", "jungle", "acacia"))
                    .forGetter(p -> p.accentIds))
            .apply(i, CherryfyProcessor::new));

    private final String primaryId;
    private final String baseId;
    private final List<String> sourceIds;
    private final List<String> accentIds;

    private final Family primary;
    private final Family base;
    private final List<Family> sources;
    private final List<Family> accents;

    public CherryfyProcessor(String primaryId, String baseId, List<String> sourceIds, List<String> accentIds) {
        this.primaryId = primaryId;
        this.baseId = baseId;
        this.sourceIds = sourceIds;
        this.accentIds = accentIds;
        this.primary = WoodFamily.byId(primaryId);
        this.base = WoodFamily.byId(baseId);
        this.sources = sourceIds.stream().map(WoodFamily::byId).toList();
        this.accents = accentIds.stream().map(WoodFamily::byId).toList();
        if (this.accents.isEmpty()) throw new IllegalArgumentException("cherryfy processor needs at least one accent");
    }

    /** True only on a thread currently placing a block-ordered cherry village (see VillagePlacer). */
    private static final ThreadLocal<Boolean> FORCED = ThreadLocal.withInitial(() -> false);

    public static void force(boolean enabled) {
        FORCED.set(enabled);
    }

    private static boolean enabled() {
        return FORCED.get() || Config.CHERRYFY_VILLAGES.getAsBoolean();
    }

    @Override
    public StructureBlockInfo processBlock(LevelReader level, BlockPos pos, BlockPos pivotPos,
            StructureBlockInfo localBlockInfo, StructureBlockInfo worldBlockInfo, StructurePlaceSettings settings) {
        BlockState state = worldBlockInfo.state();
        if (!enabled()) return worldBlockInfo;
        Family family = WoodFamily.byBlock(state.getBlock());
        if (family == null) return worldBlockInfo;

        Family target;
        if (family == primary) {
            target = base;
        } else if (sources.contains(family)) {
            target = accentFor(settings, pos);
        } else {
            return worldBlockInfo;
        }

        String kind = WoodFamily.kindOf(state.getBlock());
        Block targetBlock = target.get(kind);
        if (targetBlock == null) return worldBlockInfo;
        return new StructureBlockInfo(worldBlockInfo.pos(), WoodFamily.remap(state, targetBlock), worldBlockInfo.nbt());
    }

    /** Deterministic per-piece accent pick: seeded from the piece bounding box when available. */
    private Family accentFor(StructurePlaceSettings settings, BlockPos pos) {
        BoundingBox box = settings.getBoundingBox();
        int seed;
        if (box != null) {
            seed = 31 * box.minX() + 89 * box.minY() + 137 * box.minZ();
        } else {
            // fallback: coarse world-pos cell so neighboring blocks agree
            int cell = 32;
            seed = 31 * (pos.getX() / cell) + 137 * (pos.getZ() / cell);
        }
        return accents.get(Math.floorMod(seed, accents.size()));
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return SiasWorkshopStructureProcessors.CHERRYFY.get();
    }

    public static StructureProcessorType<CherryfyProcessor> type() {
        return () -> CODEC;
    }
}
