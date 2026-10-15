package com.siaws.siawsmod.content.agents.tasks;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.siaws.siawsmod.content.agents.pool.AgentDecisionPool;
import com.siaws.siawsmod.content.agents.pool.PoolEntry;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The shared task board (design pillar 4): comfort detectors raise singleton deficit tasks by
 * key, agents claim them with a lease (a stalled claimant releases automatically), and
 * resolutions feed the {@link AgentDecisionPool} so the pool learns which fixes worked.
 * Stored per {@link ServerLevel} via {@code level.getDataStorage()}.
 *
 * <p>The optional {@code pool} argument on the resolution methods is where outcomes are
 * recorded; callers that don't track pool state (tests, future non-agent resolutions) may
 * pass null.
 */
public class TaskBoard extends SavedData {
    public static final String DATA_ID = "siasworkshop_task_board";

    public static final SavedData.Factory<TaskBoard> FACTORY =
            new SavedData.Factory<>(TaskBoard::new, TaskBoard::read);

    /** DONE/FAILED history is capped: oldest resolved tasks drop once this many accumulate. */
    public static final int MAX_RESOLVED_TASKS = 128;

    /** Insertion-ordered so iteration order is stable across save/load. */
    private final Map<String, BoardTask> tasks = new LinkedHashMap<>();

    public static TaskBoard get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    /** Live view of all tasks, for tests and future board GUIs. */
    public List<BoardTask> tasks() {
        return List.copyOf(tasks.values());
    }

    /** The OPEN or CLAIMED task with this key, if any. */
    @Nullable
    public BoardTask activeTask(String key) {
        for (BoardTask task : tasks.values()) {
            if (task.key().equals(key) && task.isActive()) {
                return task;
            }
        }
        return null;
    }

    /**
     * Comfort-deficit singleton write path: creates an OPEN task only when no OPEN or CLAIMED
     * task with the same key exists, so a persisting deficit never piles up duplicate entries.
     * An existing OPEN task gets its detail refreshed (copy-on-write) so counts don't go stale.
     */
    public BoardTask raiseIfAbsent(String key, BoardTask.Type type, String detail, long now) {
        BoardTask existing = activeTask(key);
        if (existing != null) {
            if (existing.status() == BoardTask.Status.OPEN && !existing.detail().equals(detail)) {
                BoardTask refreshed = new BoardTask(existing.id(), existing.key(), existing.type(),
                        existing.status(), existing.claimantUuid(), existing.leaseUntilGameTime(),
                        existing.createdAtGameTime(), existing.resolvedAtGameTime(), detail, existing.failureReason());
                tasks.put(refreshed.id(), refreshed);
                setDirty();
                return refreshed;
            }
            return existing;
        }
        BoardTask task = new BoardTask(UUID.randomUUID().toString(), key, type, BoardTask.Status.OPEN,
                null, 0, now, 0, detail, null);
        tasks.put(task.id(), task);
        setDirty();
        return task;
    }

    /**
     * Claims an OPEN task for {@code claimantUuid}. A CLAIMED task whose lease has expired
     * ({@code now >= leaseUntilGameTime}) may be stolen by a new claimant (the stalled one
     * loses it without a pool record). Any other state returns false.
     */
    public boolean claim(String taskId, UUID claimantUuid, long leaseTicks, long now) {
        BoardTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }
        boolean claimable = switch (task.status()) {
            case OPEN -> true;
            case CLAIMED -> task.leaseUntilGameTime() > 0 && now >= task.leaseUntilGameTime();
            default -> false;
        };
        if (!claimable) {
            return false;
        }
        tasks.put(taskId, task.transitioned(BoardTask.Status.CLAIMED, claimantUuid, now + leaseTicks, 0, null));
        setDirty();
        return true;
    }

    /** Resolves a CLAIMED task as DONE, optionally recording a pool success. Only the claimant may resolve it. */
    public boolean complete(String taskId, UUID claimantUuid, long now,
            @Nullable AgentDecisionPool pool, int poolMaxEntries) {
        BoardTask task = tasks.get(taskId);
        if (task == null || task.status() != BoardTask.Status.CLAIMED
                || !claimantUuid.equals(task.claimantUuid())) {
            return false;
        }
        tasks.put(taskId, task.transitioned(BoardTask.Status.DONE, task.claimantUuid(), task.leaseUntilGameTime(), now, null));
        setDirty();
        if (pool != null) {
            pool.recordOutcome(task.poolTags(), "resolved: " + task.key() + " (" + task.detail() + ")",
                    task.poolPlan(), PoolEntry.Outcome.SUCCESS, null,
                    now - task.createdAtGameTime(), now, poolMaxEntries);
        }
        return true;
    }

    /** Resolves a CLAIMED task as FAILED with a short reason, optionally recording a pool anti-pattern. Only the claimant may resolve it. */
    public boolean fail(String taskId, UUID claimantUuid, String reason, long now,
            @Nullable AgentDecisionPool pool, int poolMaxEntries) {
        BoardTask task = tasks.get(taskId);
        if (task == null || task.status() != BoardTask.Status.CLAIMED
                || !claimantUuid.equals(task.claimantUuid())) {
            return false;
        }
        tasks.put(taskId, task.transitioned(BoardTask.Status.FAILED, task.claimantUuid(), task.leaseUntilGameTime(), now, reason));
        setDirty();
        if (pool != null) {
            pool.recordOutcome(task.poolTags(), "failed: " + task.key() + " (" + task.detail() + ")",
                    task.poolPlan(), PoolEntry.Outcome.FAILED, reason,
                    now - task.createdAtGameTime(), now, poolMaxEntries);
        }
        return true;
    }

    /**
     * CLAIMED tasks whose lease is spent ({@code now >= leaseUntilGameTime}) become
     * FAILED("lease_expired") — no pool record, a timeout says nothing about the plan.
     * Also reaps the resolved-task history (see {@link #MAX_RESOLVED_TASKS}).
     * Returns how many leases were swept.
     */
    public int sweepLeases(long now) {
        int swept = 0;
        for (BoardTask task : List.copyOf(tasks.values())) {
            if (task.status() != BoardTask.Status.CLAIMED) {
                continue;
            }
            long leaseUntil = task.leaseUntilGameTime();
            if (leaseUntil > 0 && now >= leaseUntil) {
                tasks.put(task.id(), task.transitioned(BoardTask.Status.FAILED, task.claimantUuid(), leaseUntil, now, "lease_expired"));
                swept++;
            }
        }
        reapResolved();
        if (swept > 0) {
            setDirty();
        }
        return swept;
    }

    /** Drops oldest-resolved tasks while the DONE+FAILED history exceeds {@link #MAX_RESOLVED_TASKS}. */
    private void reapResolved() {
        long resolved = tasks.values().stream().filter(task -> !task.isActive()).count();
        while (resolved > MAX_RESOLVED_TASKS) {
            BoardTask oldest = tasks.values().stream()
                    .filter(task -> !task.isActive())
                    .min(Comparator.comparingLong(BoardTask::resolvedAtGameTime)
                            .thenComparingLong(BoardTask::createdAtGameTime))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            tasks.remove(oldest.id());
            resolved--;
        }
    }

    /**
     * Deficit gone while still OPEN: removes the task without a pool record (nothing was ever
     * attempted, so there is no outcome to learn from). DONE/CLAIMED tasks are left alone.
     */
    public boolean clearIfOpen(String key) {
        BoardTask task = activeTask(key);
        if (task == null || task.status() != BoardTask.Status.OPEN) {
            return false;
        }
        tasks.remove(task.id());
        setDirty();
        return true;
    }

    /**
     * Deficit gone while CLAIMED: resolves to DONE and records a pool success (the claimant's
     * fix worked). Returns the completed task, or null if no CLAIMED task had the key.
     */
    @Nullable
    public BoardTask autoCompleteClaimed(String key, long now, @Nullable AgentDecisionPool pool, int poolMaxEntries) {
        BoardTask task = activeTask(key);
        if (task == null || task.status() != BoardTask.Status.CLAIMED) {
            return null;
        }
        complete(task.id(), task.claimantUuid(), now, pool, poolMaxEntries);
        return tasks.get(task.id());
    }

    /**
     * Clean unclaim: CLAIMED → OPEN with claimant and lease cleared. Used when the claimant
     * cannot proceed (e.g. no torches in inventory) — nothing was attempted, so no pool entry.
     * Only the claimant may release.
     */
    public boolean release(String taskId, UUID claimantUuid, long now) {
        BoardTask task = tasks.get(taskId);
        if (task == null || task.status() != BoardTask.Status.CLAIMED
                || !claimantUuid.equals(task.claimantUuid())) {
            return false;
        }
        tasks.put(taskId, task.transitioned(BoardTask.Status.OPEN, null, 0, task.resolvedAtGameTime(), null));
        setDirty();
        return true;
    }

    /**
     * Extends the lease of a CLAIMED task the claimant still holds — long-running executions
     * (builds) renew while they work so the sweeper never sees a stalled claim. The new lease
     * runs {@code leaseTicks} from {@code now} (not stacked on the old one).
     */
    public boolean renewLease(String taskId, UUID claimantUuid, long leaseTicks, long now) {
        BoardTask task = tasks.get(taskId);
        if (task == null || task.status() != BoardTask.Status.CLAIMED
                || !claimantUuid.equals(task.claimantUuid())) {
            return false;
        }
        tasks.put(taskId, task.transitioned(BoardTask.Status.CLAIMED, claimantUuid, now + leaseTicks, 0, null));
        setDirty();
        return true;
    }

    /** The tasks currently claimed by {@code claimantUuid}, in board order. */
    public List<BoardTask> claimedBy(UUID claimantUuid) {
        List<BoardTask> claimed = new java.util.ArrayList<>();
        for (BoardTask task : tasks.values()) {
            if (task.status() == BoardTask.Status.CLAIMED && claimantUuid.equals(task.claimantUuid())) {
                claimed.add(task);
            }
        }
        return claimed;
    }

    // --- NBT ----------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (BoardTask task : tasks.values()) {
            list.add(writeTask(task));
        }
        tag.put("tasks", list);
        return tag;
    }

    private static CompoundTag writeTask(BoardTask task) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", task.id());
        tag.putString("key", task.key());
        tag.putString("type", task.type().name());
        tag.putString("status", task.status().name());
        if (task.claimantUuid() != null) {
            tag.putUUID("claimant", task.claimantUuid());
        }
        tag.putLong("lease_until", task.leaseUntilGameTime());
        tag.putLong("created_at", task.createdAtGameTime());
        tag.putLong("resolved_at", task.resolvedAtGameTime());
        tag.putString("detail", task.detail());
        if (task.failureReason() != null) {
            tag.putString("failure_reason", task.failureReason());
        }
        tag.putInt("schema_version", 1);
        return tag;
    }

    public static TaskBoard read(CompoundTag tag, HolderLookup.Provider registries) {
        TaskBoard board = new TaskBoard();
        ListTag list = tag.getList("tasks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag taskTag = list.getCompound(i);
            if (taskTag.getInt("schema_version") != 1) {
                continue; // forward-compat: written by a newer build, skip rather than mangle
            }
            try {
                BoardTask task = new BoardTask(
                        taskTag.getString("id"),
                        taskTag.getString("key"),
                        BoardTask.Type.valueOf(taskTag.getString("type")),
                        BoardTask.Status.valueOf(taskTag.getString("status")),
                        taskTag.hasUUID("claimant") ? taskTag.getUUID("claimant") : null,
                        taskTag.getLong("lease_until"),
                        taskTag.getLong("created_at"),
                        taskTag.getLong("resolved_at"),
                        taskTag.getString("detail"),
                        taskTag.contains("failure_reason") ? taskTag.getString("failure_reason") : null);
                board.tasks.put(task.id(), task);
            } catch (RuntimeException e) {
                // malformed entry (bad enum name, missing field, …): skip it, keep the world loadable
            }
        }
        return board;
    }
}
