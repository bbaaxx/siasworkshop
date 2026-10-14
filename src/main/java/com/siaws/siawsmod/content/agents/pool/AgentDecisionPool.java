package com.siaws.siawsmod.content.agents.pool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The shared decision pool (design pillar 3): a per-world, persisted store of experience that
 * every agent reads and writes — which plans worked (and which failed, as anti-patterns) for
 * which situations. Stored per {@link ServerLevel} via {@code level.getDataStorage()}.
 *
 * <p>Retrieval is top-K by tag overlap; trusted entries ({@link PoolEntry#isTrusted}) rank
 * first, then more-successful ones. Returned entries get their uses counter and last-used
 * stamp bumped and the pool is marked dirty. When a new entry would exceed the configured
 * cap, the least recently used entry is evicted (never-retrieved entries go first, ties break
 * to oldest creation).
 *
 * <p>Each NBT entry carries {@code schema_version}; unknown versions are skipped on load so a
 * newer build can coexist with an older one.
 */
public class AgentDecisionPool extends SavedData {
    public static final String DATA_ID = "siasworkshop_agent_decision_pool";

    public static final SavedData.Factory<AgentDecisionPool> FACTORY =
            new SavedData.Factory<>(AgentDecisionPool::new, AgentDecisionPool::read);

    /** Insertion-ordered so iteration order is stable across save/load. */
    private final Map<String, PoolEntry> entries = new LinkedHashMap<>();

    public static AgentDecisionPool get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    /** Live view of all entries, for tests and future inspect GUIs. */
    public List<PoolEntry> entries() {
        return List.copyOf(entries.values());
    }

    public int size() {
        return entries.size();
    }

    /**
     * Top-K retrieval by tag overlap. Only entries sharing at least one tag are candidates.
     * Ranking: tag overlap, then trusted first, then more successes, then more uses. Returned
     * entries are stamped (uses + lastUsedGameTime) and the pool is marked dirty.
     */
    public List<PoolEntry> retrieve(Collection<String> tags, int k, int trustThreshold, long now) {
        List<String> query = List.copyOf(tags);
        record Hit(PoolEntry entry, int overlap) {}

        List<Hit> hits = new ArrayList<>();
        for (PoolEntry entry : entries.values()) {
            int overlap = entry.tagOverlap(query);
            if (overlap > 0) {
                hits.add(new Hit(entry, overlap));
            }
        }
        hits.sort(Comparator
                .comparingInt((Hit hit) -> hit.overlap()).reversed()
                .thenComparing((Hit hit) -> hit.entry().isTrusted(trustThreshold) ? 0 : 1)
                .thenComparingInt((Hit hit) -> -hit.entry().successes())
                .thenComparingInt((Hit hit) -> -hit.entry().uses()));

        int limit = Math.min(k, hits.size());
        List<PoolEntry> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            PoolEntry stamped = hits.get(i).entry().used(now);
            entries.put(stamped.id(), stamped);
            result.add(stamped);
        }
        if (limit > 0) {
            setDirty();
        }
        return result;
    }

    /**
     * Write path: records the outcome of one attempted plan as a new pool entry, then evicts
     * down to {@code maxEntries} if over capacity. The plan must already be validated by the
     * caller — pool entries can never carry arbitrary model text (design pillar 3, safety).
     */
    public PoolEntry recordOutcome(List<String> tags, String situationSummary, List<String> plan,
            PoolEntry.Outcome outcome, @Nullable String failureReason, long durationTicks,
            long now, int maxEntries) {
        PoolEntry entry = outcome == PoolEntry.Outcome.SUCCESS
                ? PoolEntry.success(tags, situationSummary, plan, durationTicks, now)
                : PoolEntry.failure(tags, situationSummary, plan, failureReason == null ? "unknown" : failureReason,
                        durationTicks, now);
        entries.put(entry.id(), entry);
        evictDownTo(maxEntries);
        setDirty();
        return entry;
    }

    /**
     * Cites an existing entry in a successful resolution: bumps its successes counter and
     * stamps {@code lastUsedGameTime = now}.
     */
    public boolean recordSuccess(String entryId, long now) {
        PoolEntry entry = entries.get(entryId);
        if (entry == null) {
            return false;
        }
        entries.put(entryId, entry.recordedSuccess(now));
        setDirty();
        return true;
    }

    /** Evicts least recently used entries until at most {@code maxEntries} remain. */
    public void evictDownTo(int maxEntries) {
        while (entries.size() > maxEntries) {
            entries.values().stream()
                    .min(EVICTION_ORDER)
                    .ifPresent(victim -> entries.remove(victim.id()));
        }
    }

    /** Never-used entries first, then least recently used, then oldest creation. */
    private static final Comparator<PoolEntry> EVICTION_ORDER = Comparator
            .comparingInt((PoolEntry entry) -> entry.lastUsedGameTime() == 0 ? 0 : 1)
            .thenComparingLong(PoolEntry::lastUsedGameTime)
            .thenComparingLong(PoolEntry::createdAtGameTime);

    // --- NBT ----------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (PoolEntry entry : entries.values()) {
            list.add(writeEntry(entry));
        }
        tag.put("entries", list);
        return tag;
    }

    private static CompoundTag writeEntry(PoolEntry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entry.id());
        tag.put("tags", toStringList(entry.tags()));
        tag.putString("summary", entry.situationSummary());
        tag.put("plan", toStringList(entry.plan()));
        tag.putString("outcome", entry.outcome().name());
        if (entry.failureReason() != null) {
            tag.putString("failure_reason", entry.failureReason());
        }
        tag.putLong("duration_ticks", entry.durationTicks());
        tag.putInt("uses", entry.uses());
        tag.putInt("successes", entry.successes());
        tag.putLong("last_used", entry.lastUsedGameTime());
        tag.putLong("created_at", entry.createdAtGameTime());
        tag.putInt("schema_version", entry.schemaVersion());
        return tag;
    }

    public static AgentDecisionPool read(CompoundTag tag, HolderLookup.Provider registries) {
        AgentDecisionPool pool = new AgentDecisionPool();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (entryTag.getInt("schema_version") != PoolEntry.CURRENT_SCHEMA_VERSION) {
                continue; // forward-compat: written by a newer build, skip rather than mangle
            }
            try {
                PoolEntry entry = readEntry(entryTag);
                pool.entries.put(entry.id(), entry);
            } catch (RuntimeException e) {
                // malformed entry (bad enum name, missing field, …): skip it, keep the world loadable
            }
        }
        return pool;
    }

    private static PoolEntry readEntry(CompoundTag tag) {
        return new PoolEntry(
                tag.getString("id"),
                fromStringList(tag.getList("tags", Tag.TAG_STRING)),
                tag.getString("summary"),
                fromStringList(tag.getList("plan", Tag.TAG_STRING)),
                PoolEntry.Outcome.valueOf(tag.getString("outcome")),
                tag.contains("failure_reason") ? tag.getString("failure_reason") : null,
                tag.getLong("duration_ticks"),
                tag.getInt("uses"),
                tag.getInt("successes"),
                tag.getLong("last_used"),
                tag.getLong("created_at"),
                tag.getInt("schema_version"));
    }

    private static ListTag toStringList(List<String> values) {
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value));
        }
        return list;
    }

    private static List<String> fromStringList(ListTag list) {
        List<String> values = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            values.add(list.getString(i));
        }
        return values;
    }
}
