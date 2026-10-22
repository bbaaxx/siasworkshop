package com.siaws.siawsmod.content.agents.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.content.agents.build.Blueprint;
import com.siaws.siawsmod.content.agents.build.BlueprintLibrary;
import com.siaws.siawsmod.content.agents.build.BuildQueue;
import com.siaws.siawsmod.content.agents.build.BuildQueue.BuildJob;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The agent knowledge ledger (first slice of the shared knowledge system, live-test
 * 2026-10-22): the durable record of what the agents know and what blocks them. Today the
 * only entry type is a MATERIAL need — a build task that ran short of palette items. The
 * task is parked, not failed: the deficit is written here, the task becomes unclaimable,
 * and a periodic re-check resolves the entry the moment the station chests near the anchor
 * hold the remaining quantity. When the action surface grows (gather/harvest/mine/trade/
 * search), new resolution paths feed the same entries — an entry with no known source is
 * precisely the "we need this and don't know how to get it" record a future planner reads.
 *
 * <p>Per {@link ServerLevel} via {@code level.getDataStorage()}, mirroring {@link TaskBoard}.
 * Entries are schema-versioned; unknown versions are skipped on load, never mangled.
 */
public class AgentKnowledge extends SavedData {
    public static final String DATA_ID = "siasworkshop_agent_knowledge";

    public static final SavedData.Factory<AgentKnowledge> FACTORY =
            new SavedData.Factory<>(AgentKnowledge::new, AgentKnowledge::read);

    /** Bound on retained entries; oldest resolved entries drop first, then oldest blocked. */
    public static final int MAX_ENTRIES = 256;

    /**
     * One recorded material deficit. {@code quantityNeeded} is refreshed at every re-check
     * against the live world (blocks may have been placed by other means meanwhile).
     * {@code status}: {@code BLOCKED}, {@code RESOLVED_RESTOCKED} (station chests now hold
     * the quantity), {@code RESOLVED_PLACED} (nothing left to place), or
     * {@code RESOLVED_GONE} (the task is no longer active).
     */
    public record MaterialNeed(String taskId, String taskKey, String blueprint, BlockPos anchor,
            String itemId, int quantityNeeded, long firstBlockedAt, long lastCheckedAt, String status) {

        public MaterialNeed withQuantity(int quantity, long checkedAt) {
            return new MaterialNeed(taskId, taskKey, blueprint, anchor, itemId, quantity,
                    firstBlockedAt, checkedAt, status);
        }

        public MaterialNeed withStatus(String newStatus, long checkedAt) {
            return new MaterialNeed(taskId, taskKey, blueprint, anchor, itemId, quantityNeeded,
                    firstBlockedAt, checkedAt, newStatus);
        }
    }

    /** Insertion-ordered, stable across save/load. */
    private final Map<String, MaterialNeed> needs = new LinkedHashMap<>();

    public static AgentKnowledge get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    /** Live view of all entries (any status), for tests and diagnostics. */
    public List<MaterialNeed> needs() {
        return List.copyOf(needs.values());
    }

    /** The BLOCKED entries for {@code taskId}, in record order. */
    public List<MaterialNeed> blockedNeeds(String taskId) {
        List<MaterialNeed> blocked = new ArrayList<>();
        for (MaterialNeed need : needs.values()) {
            if (need.taskId().equals(taskId) && "BLOCKED".equals(need.status())) {
                blocked.add(need);
            }
        }
        return blocked;
    }

    /** True while {@code taskId} has at least one unresolved BLOCKED entry. */
    public boolean isBlocked(String taskId) {
        return !blockedNeeds(taskId).isEmpty();
    }

    /**
     * Records (or refreshes) a material block for {@code task}: the build at {@code job}'s
     * anchor still needs {@code quantity} of {@code itemId} and the agent could not obtain
     * it. An existing BLOCKED entry for the same task and item refreshes its quantity and
     * check time instead of piling up duplicates.
     */
    public void recordBlock(BoardTask task, BuildJob job, String itemId, int quantity, long now) {
        for (Map.Entry<String, MaterialNeed> entry : needs.entrySet()) {
            MaterialNeed need = entry.getValue();
            if (need.taskId().equals(task.id()) && need.itemId().equals(itemId)
                    && "BLOCKED".equals(need.status())) {
                entry.setValue(need.withQuantity(quantity, now));
                setDirty();
                trim();
                return;
            }
        }
        MaterialNeed need = new MaterialNeed(task.id(), task.key(), job.blueprintName(), job.anchor(),
                itemId, quantity, now, now, "BLOCKED");
        needs.put(task.id() + ":" + itemId, need);
        setDirty();
        trim();
    }

    /**
     * The periodic re-check: every BLOCKED entry group re-evaluates its task against the
     * live world. A task unblocks (all its entries resolve) only when, for every recorded
     * item, nothing of it remains to place or the station chests near the anchor hold the
     * remaining quantity. Returns how many entries were resolved by this pass.
     */
    public int recheckBlocked(ServerLevel level, TaskBoard board, long now) {
        Map<String, List<Map.Entry<String, MaterialNeed>>> byTask = new LinkedHashMap<>();
        for (Map.Entry<String, MaterialNeed> entry : needs.entrySet()) {
            if ("BLOCKED".equals(entry.getValue().status())) {
                byTask.computeIfAbsent(entry.getValue().taskId(), id -> new ArrayList<>()).add(entry);
            }
        }
        int resolved = 0;
        for (Map.Entry<String, List<Map.Entry<String, MaterialNeed>>> group : byTask.entrySet()) {
            BoardTask task = board.tasks().stream()
                    .filter(t -> t.id().equals(group.getKey())).findFirst().orElse(null);
            if (task == null || !task.isActive()) {
                for (Map.Entry<String, MaterialNeed> entry : group.getValue()) {
                    entry.setValue(entry.getValue().withStatus("RESOLVED_GONE", now));
                    resolved++;
                }
                setDirty();
                continue;
            }
            BuildJob job = BuildQueue.parseJob(task);
            Blueprint blueprint = job != null ? BlueprintLibrary.INSTANCE.get(job.blueprintName()) : null;
            if (job == null || blueprint == null) {
                continue; // unparseable for now — retry next pass, don't drop knowledge
            }
            boolean allSatisfied = true;
            for (Map.Entry<String, MaterialNeed> entry : group.getValue()) {
                MaterialNeed need = entry.getValue();
                int remaining = countRemaining(level, job, blueprint, need.itemId());
                if (remaining == 0) {
                    entry.setValue(need.withQuantity(0, now).withStatus("RESOLVED_PLACED", now));
                    resolved++;
                    continue;
                }
                entry.setValue(need.withQuantity(remaining, now));
                if (availableNear(level, job.anchor(), need.itemId()) >= remaining) {
                    entry.setValue(entry.getValue().withStatus("RESOLVED_RESTOCKED", now));
                    resolved++;
                } else {
                    allSatisfied = false;
                }
            }
            setDirty();
        }
        trim();
        return resolved;
    }

    /** Unplaced cells of {@code blueprint} at {@code job}'s anchor holding {@code blockId}. */
    private static int countRemaining(ServerLevel level, BuildJob job, Blueprint blueprint, String blockId) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(blockId));
        int remaining = 0;
        for (Blueprint.Placement placement : blueprint.placements()) {
            if (!placement.blockId().equals(blockId)) {
                continue;
            }
            BlockState state = level.getBlockState(job.anchor().offset(placement.rel()));
            if (block == null || !state.is(block)) {
                remaining++;
            }
        }
        return remaining;
    }

    /** How many of {@code itemId} the containers within the station radius of {@code anchor} hold. */
    private static int availableNear(ServerLevel level, BlockPos anchor, String itemId) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (item == Items.AIR) {
            return 0;
        }
        int radius = Config.AGENTS_STATION_RADIUS.get();
        int available = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
                    BlockEntity entity = level.getBlockEntity(cursor);
                    if (!(entity instanceof Container chest)) {
                        continue;
                    }
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        ItemStack stack = chest.getItem(slot);
                        if (stack.is(item)) {
                            available += stack.getCount();
                        }
                    }
                }
            }
        }
        return available;
    }

    /** Bounds entry count: resolved entries drop oldest-first, then oldest blocked. */
    private void trim() {
        while (needs.size() > MAX_ENTRIES) {
            MaterialNeed oldestResolved = needs.values().stream()
                    .filter(need -> !"BLOCKED".equals(need.status()))
                    .min(Comparator.comparingLong(MaterialNeed::lastCheckedAt))
                    .orElse(null);
            MaterialNeed drop = oldestResolved != null ? oldestResolved : needs.values().iterator().next();
            needs.values().remove(drop);
        }
    }

    // --- NBT ----------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (MaterialNeed need : needs.values()) {
            list.add(writeNeed(need));
        }
        tag.put("needs", list);
        return tag;
    }

    private static CompoundTag writeNeed(MaterialNeed need) {
        CompoundTag tag = new CompoundTag();
        tag.putString("task_id", need.taskId());
        tag.putString("task_key", need.taskKey());
        tag.putString("blueprint", need.blueprint());
        tag.putInt("anchor_x", need.anchor().getX());
        tag.putInt("anchor_y", need.anchor().getY());
        tag.putInt("anchor_z", need.anchor().getZ());
        tag.putString("item", need.itemId());
        tag.putInt("quantity", need.quantityNeeded());
        tag.putLong("first_blocked", need.firstBlockedAt());
        tag.putLong("last_checked", need.lastCheckedAt());
        tag.putString("status", need.status());
        tag.putInt("schema_version", 1);
        return tag;
    }

    public static AgentKnowledge read(CompoundTag tag, HolderLookup.Provider registries) {
        AgentKnowledge knowledge = new AgentKnowledge();
        ListTag list = tag.getList("needs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag needTag = list.getCompound(i);
            if (needTag.getInt("schema_version") != 1) {
                continue; // forward-compat: written by a newer build, skip rather than mangle
            }
            try {
                MaterialNeed need = new MaterialNeed(
                        needTag.getString("task_id"),
                        needTag.getString("task_key"),
                        needTag.getString("blueprint"),
                        new BlockPos(needTag.getInt("anchor_x"), needTag.getInt("anchor_y"),
                                needTag.getInt("anchor_z")),
                        needTag.getString("item"),
                        needTag.getInt("quantity"),
                        needTag.getLong("first_blocked"),
                        needTag.getLong("last_checked"),
                        needTag.getString("status"));
                knowledge.needs.put(need.taskId() + ":" + need.itemId(), need);
            } catch (RuntimeException e) {
                // malformed entry: skip it, keep the world loadable
            }
        }
        return knowledge;
    }
}
