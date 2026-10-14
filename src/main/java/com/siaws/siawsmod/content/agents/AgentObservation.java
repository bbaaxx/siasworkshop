package com.siaws.siawsmod.content.agents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.siaws.siawsmod.init.SiasWorkshopBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds the token-budgeted plain-text observation snapshot for a workshop agent, per the
 * design brief (mdocs/wiki/reference/agent-embodiment-observation.md, pillar 2). Runs on the
 * server thread; sections are emitted in salience order, one per line, using counts rather
 * than lists and coordinates relative to the agent (or to the village foundation when one
 * is near). Rough budget: well under ~600 tokens for a typical village scene.
 *
 * <p>Helpers take plain (level, position, …) arguments where practical so they stay pure
 * enough to unit-test without an entity.
 */
public final class AgentObservation {
    /** Radius of the nearby-block / nearby-entity scan. */
    public static final int SCAN_RADIUS = 16;
    /** How far to look for a village foundation block before falling back to absolute coords. */
    public static final int FOUNDATION_RADIUS = 48;
    public static final int FOUNDATION_Y_RADIUS = 12;

    private AgentObservation() {}

    /** Builds the full six-section snapshot for {@code agent}. Server-side only. */
    public static String build(WorkshopAgentEntity agent) {
        ServerLevel level = (ServerLevel) agent.level();
        BlockPos pos = agent.blockPosition();

        StringBuilder sb = new StringBuilder();
        appendSelf(sb, agent, level, pos);
        sb.append('\n');
        appendPlayers(sb, level, agent);
        sb.append('\n');
        BlockScan scan = scanBlocks(level, pos);
        appendNearbyBlocks(sb, scan, pos);
        sb.append('\n');
        appendEntities(sb, level, agent);
        sb.append('\n');
        appendEnvironment(sb, level, pos);
        sb.append('\n');
        appendActions(sb, scan);
        return sb.toString();
    }

    // --- SELF ----------------------------------------------------------------

    private static void appendSelf(StringBuilder sb, WorkshopAgentEntity agent, ServerLevel level, BlockPos pos) {
        sb.append("[SELF] name=").append(agent.getName().getString());
        BlockPos foundation = findNearestFoundation(level, pos);
        if (foundation != null) {
            sb.append(" pos=(").append(rel(foundation, pos)).append(" rel foundation)")
                    .append('@').append(dist(Math.sqrt(pos.distSqr(foundation)))).append("m");
        } else {
            sb.append(" pos=").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        sb.append(" hp=").append(Math.round(agent.getHealth())).append('/').append(Math.round(agent.getMaxHealth()));
        sb.append(" held=").append(describeItem(agent.getMainHandItem()));
        sb.append(" task=").append(agent.currentTask());
        sb.append(" inv=").append(inventorySummary(agent.getInventory()));
    }

    /** Nearest village foundation block, or null when the agent is outside a village. */
    @Nullable
    private static BlockPos findNearestFoundation(ServerLevel level, BlockPos center) {
        Block foundation = SiasWorkshopBlocks.VILLAGE_FOUNDATION.get();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -FOUNDATION_RADIUS; dx <= FOUNDATION_RADIUS; dx++) {
            for (int dz = -FOUNDATION_RADIUS; dz <= FOUNDATION_RADIUS; dz++) {
                for (int dy = -FOUNDATION_Y_RADIUS; dy <= FOUNDATION_Y_RADIUS; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (level.getBlockState(cursor).is(foundation)) {
                        double d = center.distSqr(cursor);
                        if (d < bestSq) {
                            bestSq = d;
                            best = cursor.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    // --- PLAYERS --------------------------------------------------------------

    private static void appendPlayers(StringBuilder sb, ServerLevel level, WorkshopAgentEntity agent) {
        sb.append("[PLAYERS]");
        if (level.players().isEmpty()) {
            sb.append(" none");
            return;
        }
        for (ServerPlayer player : level.players()) {
            sb.append(' ').append(player.getName().getString());
            sb.append('@').append(dist(player.distanceTo(agent))).append('m');
            sb.append(" hp=").append(Math.round(player.getHealth()));
            sb.append(" food=").append(player.getFoodData().getFoodLevel());
            sb.append(" held=").append(describeItem(player.getMainHandItem()));
        }
    }

    // --- NEARBY BLOCKS ---------------------------------------------------------

    /** Result of one block scan: per-type counts plus POI tallies. */
    static final class BlockScan {
        int total;
        final Map<String, Integer> counts = new HashMap<>();
        int beds;
        int chests;
        int craftingTables;
        int furnaces;
        int torches;
        @Nullable BlockPos nearestBed;
        @Nullable BlockPos nearestChest;
        @Nullable BlockPos nearestCraftingTable;
        @Nullable BlockPos nearestFurnace;
    }

    private static BlockScan scanBlocks(ServerLevel level, BlockPos center) {
        BlockScan scan = new BlockScan();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int r = SCAN_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    scan.total++;
                    Block block = state.getBlock();
                    scan.counts.merge(shortId(BuiltInRegistries.BLOCK.getKey(block)), 1, Integer::sum);
                    notePoi(scan, block, center, cursor);
                }
            }
        }
        return scan;
    }

    private static void notePoi(BlockScan scan, Block block, BlockPos center, BlockPos pos) {
        double distSq = center.distSqr(pos);
        if (block instanceof BedBlock) {
            scan.beds++;
            if (scan.nearestBed == null || distSq < center.distSqr(scan.nearestBed)) {
                scan.nearestBed = pos.immutable();
            }
        } else if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL) {
            scan.chests++;
            if (scan.nearestChest == null || distSq < center.distSqr(scan.nearestChest)) {
                scan.nearestChest = pos.immutable();
            }
        } else if (block == Blocks.CRAFTING_TABLE) {
            scan.craftingTables++;
            if (scan.nearestCraftingTable == null || distSq < center.distSqr(scan.nearestCraftingTable)) {
                scan.nearestCraftingTable = pos.immutable();
            }
        } else if (block instanceof AbstractFurnaceBlock) {
            scan.furnaces++;
            if (scan.nearestFurnace == null || distSq < center.distSqr(scan.nearestFurnace)) {
                scan.nearestFurnace = pos.immutable();
            }
        } else if (block == Blocks.TORCH || block == Blocks.WALL_TORCH) {
            scan.torches++;
        }
    }

    private static void appendNearbyBlocks(StringBuilder sb, BlockScan scan, BlockPos center) {
        sb.append("[NEARBY BLOCKS] total=").append(scan.total).append(" types=").append(scan.counts.size());
        if (!scan.counts.isEmpty()) {
            List<Map.Entry<String, Integer>> sorted = scan.counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                    .toList();
            sb.append(" top:");
            int shown = 0;
            int other = 0;
            for (Map.Entry<String, Integer> entry : sorted) {
                if (shown < 8) {
                    if (shown > 0) {
                        sb.append(',');
                    }
                    sb.append(' ').append(entry.getKey()).append('×').append(entry.getValue());
                    shown++;
                } else {
                    other += entry.getValue();
                }
            }
            if (other > 0) {
                sb.append(" other×").append(other);
            }
        }
        sb.append(" | poi:");
        appendPoi(sb, "bed", scan.beds, scan.nearestBed, center);
        appendPoi(sb, "chest", scan.chests, scan.nearestChest, center);
        appendPoi(sb, "crafting_table", scan.craftingTables, scan.nearestCraftingTable, center);
        appendPoi(sb, "furnace", scan.furnaces, scan.nearestFurnace, center);
        sb.append(" torch×").append(scan.torches);
    }

    private static void appendPoi(StringBuilder sb, String name, int count, @Nullable BlockPos nearest, BlockPos center) {
        sb.append(' ').append(name).append('×').append(count);
        if (nearest != null) {
            sb.append('(').append(rel(center, nearest)).append(')');
        }
    }

    // --- NEARBY ENTITIES ---------------------------------------------------------

    private static void appendEntities(StringBuilder sb, ServerLevel level, WorkshopAgentEntity agent) {
        sb.append("[NEARBY ENTITIES]");
        List<Entity> nearby = level.getEntities(agent, agent.getBoundingBox().inflate(SCAN_RADIUS), Entity::isAlive);

        List<WorkshopAgentEntity> agents = new ArrayList<>();
        List<Villager> villagers = new ArrayList<>();
        List<Monster> hostiles = new ArrayList<>();
        int others = 0;
        for (Entity entity : nearby) {
            if (entity instanceof WorkshopAgentEntity other) {
                agents.add(other);
            } else if (entity instanceof Villager villager) {
                villagers.add(villager);
            } else if (entity instanceof Monster monster) {
                hostiles.add(monster);
            } else if (!(entity instanceof Player)) {
                others++; // players are listed in [PLAYERS] instead
            }
        }

        sb.append(" agents=").append(agents.size());
        if (!agents.isEmpty()) {
            WorkshopAgentEntity nearest = nearest(agents, agent);
            sb.append('(').append(nearest.currentTask()).append('@')
                    .append(dist(nearest.distanceTo(agent))).append("m)");
        }
        sb.append(" villagers=").append(villagers.size());
        if (!villagers.isEmpty()) {
            sb.append('@').append(dist(nearest(villagers, agent).distanceTo(agent))).append('m');
        }
        sb.append(" hostiles=").append(hostiles.size());
        if (!hostiles.isEmpty()) {
            hostiles.sort(Comparator.comparingDouble(e -> e.distanceTo(agent)));
            sb.append(' ');
            for (int i = 0; i < hostiles.size() && i < 3; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Monster monster = hostiles.get(i);
                sb.append(shortId(BuiltInRegistries.ENTITY_TYPE.getKey(monster.getType())))
                        .append('@').append(dist(monster.distanceTo(agent))).append('m');
            }
        }
        sb.append(" others=").append(others);
    }

    private static <T extends Entity> T nearest(List<? extends T> entities, Entity from) {
        T best = entities.get(0);
        double bestDist = best.distanceTo(from);
        for (T entity : entities) {
            double d = entity.distanceTo(from);
            if (d < bestDist) {
                bestDist = d;
                best = entity;
            }
        }
        return best;
    }

    // --- ENVIRONMENT ---------------------------------------------------------

    private static void appendEnvironment(StringBuilder sb, ServerLevel level, BlockPos pos) {
        long time = level.getDayTime() % 24000;
        String biome = level.getBiome(pos).unwrapKey().map(key -> shortId(key.location())).orElse("?");
        String weather = level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear";
        sb.append("[ENVIRONMENT] dim=").append(shortId(level.dimension().location()))
                .append(" biome=").append(biome)
                .append(" time=").append(time).append(level.isDay() ? "(day)" : "(night)")
                .append(" weather=").append(weather)
                .append(" light=").append(level.getMaxLocalRawBrightness(pos));
    }

    // --- AVAILABLE ACTIONS ---------------------------------------------------------

    /**
     * The enumerated action space from the design brief, pre-filtered by nearby stations:
     * {@code craft} requires a crafting table in range, {@code deposit_to}/{@code withdraw_from}
     * require a chest (or barrel) in range.
     */
    private static void appendActions(StringBuilder sb, BlockScan scan) {
        List<String> actions = new ArrayList<>(List.of(
                "move_to(x,z)", "follow(player)", "gather(block)", "chop()", "mine(block)"));
        if (scan.craftingTables > 0) {
            actions.add("craft(item)");
        }
        actions.add("place(block)");
        actions.add("build_step(blueprint)");
        if (scan.chests > 0) {
            actions.add("deposit_to(chest)");
            actions.add("withdraw_from(chest)");
        }
        actions.addAll(List.of("light_area()", "use(item)", "say(player,msg)", "rest()", "claim_task(board)"));
        sb.append("[AVAILABLE ACTIONS] ").append(String.join(", ", actions));
    }

    // --- formatting helpers ---------------------------------------------------------

    /** Relative offset with explicit signs, e.g. "+12,-4,+30". */
    private static String rel(BlockPos from, BlockPos to) {
        return "%+d,%+d,%+d".formatted(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }

    private static String dist(double d) {
        return d >= 10 ? String.valueOf(Math.round(d)) : String.format(Locale.ROOT, "%.1f", d);
    }

    private static String shortId(ResourceLocation id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    /** Base item description: short id plus damage percent, with ×count appended when the stack count is greater than 1. */
    private static String describeItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        String name = shortId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (stack.isDamaged()) {
            int pct = Math.round(100f * stack.getDamageValue() / stack.getMaxDamage());
            name += "(dmg " + pct + "%)";
        }
        return stack.getCount() > 1 ? name + "×" + stack.getCount() : name;
    }

    private static String inventorySummary(Container inventory) {
        Map<String, Integer> stacks = new LinkedHashMap<>();
        int used = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            used++;
            String base = describeItem(stack.copyWithCount(1));
            stacks.merge(base, stack.getCount(), Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(used).append('/').append(inventory.getContainerSize()).append(" slots");
        if (stacks.isEmpty()) {
            return sb.toString();
        }
        sb.append(": ");
        int shown = 0;
        int more = 0;
        for (Map.Entry<String, Integer> entry : stacks.entrySet()) {
            if (shown < 6) {
                if (shown > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append('×').append(entry.getValue());
                shown++;
            } else {
                more++;
            }
        }
        if (more > 0) {
            sb.append(", +").append(more).append(" more");
        }
        return sb.toString();
    }
}
