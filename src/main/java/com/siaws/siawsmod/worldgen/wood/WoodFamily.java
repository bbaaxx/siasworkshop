package com.siaws.siawsmod.worldgen.wood;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Wood family tables used by the cherryfy structure processor. Declared as a flat
 * text table (family id + member-kind = block id) so mappings stay data, not code.
 */
public final class WoodFamily {
    // kind=block-id pairs per family; extend by adding lines/entries
    private static final String TABLE = """
            oak log=minecraft:oak_log wood=minecraft:oak_wood stripped_log=minecraft:stripped_oak_log stripped_wood=minecraft:stripped_oak_wood planks=minecraft:oak_planks stairs=minecraft:oak_stairs slab=minecraft:oak_slab fence=minecraft:oak_fence fence_gate=minecraft:oak_fence_gate door=minecraft:oak_door trapdoor=minecraft:oak_trapdoor sign=minecraft:oak_sign wall_sign=minecraft:oak_wall_sign hanging_sign=minecraft:oak_hanging_sign wall_hanging_sign=minecraft:oak_wall_hanging_sign leaves=minecraft:oak_leaves sapling=minecraft:oak_sapling
            cherry log=minecraft:cherry_log wood=minecraft:cherry_wood stripped_log=minecraft:stripped_cherry_log stripped_wood=minecraft:stripped_cherry_wood planks=minecraft:cherry_planks stairs=minecraft:cherry_stairs slab=minecraft:cherry_slab fence=minecraft:cherry_fence fence_gate=minecraft:cherry_fence_gate door=minecraft:cherry_door trapdoor=minecraft:cherry_trapdoor sign=minecraft:cherry_sign wall_sign=minecraft:cherry_wall_sign hanging_sign=minecraft:cherry_hanging_sign wall_hanging_sign=minecraft:cherry_wall_hanging_sign leaves=minecraft:cherry_leaves sapling=minecraft:cherry_sapling
            birch log=minecraft:birch_log wood=minecraft:birch_wood stripped_log=minecraft:stripped_birch_log stripped_wood=minecraft:stripped_birch_wood planks=minecraft:birch_planks stairs=minecraft:birch_stairs slab=minecraft:birch_slab fence=minecraft:birch_fence fence_gate=minecraft:birch_fence_gate door=minecraft:birch_door trapdoor=minecraft:birch_trapdoor sign=minecraft:birch_sign wall_sign=minecraft:birch_wall_sign hanging_sign=minecraft:birch_hanging_sign wall_hanging_sign=minecraft:birch_wall_hanging_sign leaves=minecraft:birch_leaves sapling=minecraft:birch_sapling
            jungle log=minecraft:jungle_log wood=minecraft:jungle_wood stripped_log=minecraft:stripped_jungle_log stripped_wood=minecraft:stripped_jungle_wood planks=minecraft:jungle_planks stairs=minecraft:jungle_stairs slab=minecraft:jungle_slab fence=minecraft:jungle_fence fence_gate=minecraft:jungle_fence_gate door=minecraft:jungle_door trapdoor=minecraft:jungle_trapdoor sign=minecraft:jungle_sign wall_sign=minecraft:jungle_wall_sign hanging_sign=minecraft:jungle_hanging_sign wall_hanging_sign=minecraft:jungle_wall_hanging_sign leaves=minecraft:jungle_leaves sapling=minecraft:jungle_sapling
            acacia log=minecraft:acacia_log wood=minecraft:acacia_wood stripped_log=minecraft:stripped_acacia_log stripped_wood=minecraft:stripped_acacia_wood planks=minecraft:acacia_planks stairs=minecraft:acacia_stairs slab=minecraft:acacia_slab fence=minecraft:acacia_fence fence_gate=minecraft:acacia_fence_gate door=minecraft:acacia_door trapdoor=minecraft:acacia_trapdoor sign=minecraft:acacia_sign wall_sign=minecraft:acacia_wall_sign hanging_sign=minecraft:acacia_hanging_sign wall_hanging_sign=minecraft:acacia_wall_hanging_sign leaves=minecraft:acacia_leaves sapling=minecraft:acacia_sapling
            spruce log=minecraft:spruce_log wood=minecraft:spruce_wood stripped_log=minecraft:stripped_spruce_log stripped_wood=minecraft:stripped_spruce_wood planks=minecraft:spruce_planks stairs=minecraft:spruce_stairs slab=minecraft:spruce_slab fence=minecraft:spruce_fence fence_gate=minecraft:spruce_fence_gate door=minecraft:spruce_door trapdoor=minecraft:spruce_trapdoor sign=minecraft:spruce_sign wall_sign=minecraft:spruce_wall_sign hanging_sign=minecraft:spruce_hanging_sign wall_hanging_sign=minecraft:spruce_wall_hanging_sign leaves=minecraft:spruce_leaves sapling=minecraft:spruce_sapling
            dark_oak log=minecraft:dark_oak_log wood=minecraft:dark_oak_wood stripped_log=minecraft:stripped_dark_oak_log stripped_wood=minecraft:stripped_dark_oak_wood planks=minecraft:dark_oak_planks stairs=minecraft:dark_oak_stairs slab=minecraft:dark_oak_slab fence=minecraft:dark_oak_fence fence_gate=minecraft:dark_oak_fence_gate door=minecraft:dark_oak_door trapdoor=minecraft:dark_oak_trapdoor sign=minecraft:dark_oak_sign wall_sign=minecraft:dark_oak_wall_sign hanging_sign=minecraft:dark_oak_hanging_sign wall_hanging_sign=minecraft:dark_oak_wall_hanging_sign leaves=minecraft:dark_oak_leaves sapling=minecraft:dark_oak_sapling
            """;

    private static final Map<String, Family> FAMILIES = new LinkedHashMap<>();
    private static final Map<Block, Family> BY_BLOCK = new HashMap<>();

    static {
        for (String line : TABLE.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] tokens = line.split("\\s+");
            String id = tokens[0];
            Map<String, Block> members = new LinkedHashMap<>();
            for (int i = 1; i < tokens.length; i++) {
                String[] kv = tokens[i].split("=", 2);
                Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(kv[1]));
                members.put(kv[0], block);
            }
            Family family = new Family(id, Map.copyOf(members));
            FAMILIES.put(id, family);
            family.members().forEach((kind, block) -> BY_BLOCK.put(block, family));
        }
    }

    public record Family(String id, Map<String, Block> members) {
        public Block get(String kind) {
            return members.get(kind);
        }
    }

    private WoodFamily() {}

    public static Family byId(String id) {
        Family family = FAMILIES.get(id);
        if (family == null) throw new IllegalArgumentException("Unknown wood family: " + id);
        return family;
    }

    public static Family byBlock(Block block) {
        return BY_BLOCK.get(block);
    }

    /** Same member kind within a family (oak_planks -> "planks"), or null if not a wood member. */
    public static String kindOf(Block block) {
        Family family = BY_BLOCK.get(block);
        if (family == null) return null;
        for (Map.Entry<String, Block> e : family.members().entrySet()) {
            if (e.getValue() == block) return e.getKey();
        }
        return null;
    }

    /** Remap a blockstate to another block, copying shared property values (log axis, door hinge, stair shape...). */
    public static BlockState remap(BlockState from, Block to) {
        BlockState result = to.defaultBlockState();
        for (Property<?> property : from.getProperties()) {
            result = copyProperty(result, from, property);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(BlockState to, BlockState from, Property<T> property) {
        if (to.hasProperty(property)) {
            return to.setValue(property, from.getValue(property));
        }
        return to;
    }
}
