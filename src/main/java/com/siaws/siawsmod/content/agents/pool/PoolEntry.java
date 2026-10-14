package com.siaws.siawsmod.content.agents.pool;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * One entry of the shared decision pool (design pillar 3): a validated plan with a recorded
 * outcome, shared across all agents in a world. Immutable; uses/successes/last-used counters
 * move through {@link #used(long)} / {@link #recordedSuccess(long)} copies managed by
 * {@link AgentDecisionPool}.
 *
 * @param id                unique id (UUID string)
 * @param tags              retrieval tags (goal family, situation facets, action types)
 * @param situationSummary  one line, LLM-written
 * @param plan              validated action sequence (actions from the observation action space only)
 * @param outcome           SUCCESS or FAILED
 * @param failureReason     null on success, short machine-ish reason on failure
 * @param durationTicks     how long the plan took from claim to resolution
 * @param uses              how often retrieval returned this entry
 * @param successes         how often a task resolved successfully citing this entry
 * @param lastUsedGameTime  game time of last retrieval, 0 = never retrieved
 * @param createdAtGameTime game time the entry was recorded
 * @param schemaVersion     NBT schema version; entries with unknown versions are skipped on load
 */
public record PoolEntry(
        String id,
        List<String> tags,
        String situationSummary,
        List<String> plan,
        Outcome outcome,
        @Nullable String failureReason,
        long durationTicks,
        int uses,
        int successes,
        long lastUsedGameTime,
        long createdAtGameTime,
        int schemaVersion) {

    /** Schema version written by this build; load skips anything newer. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public enum Outcome {
        SUCCESS,
        FAILED
    }

    public PoolEntry {
        tags = List.copyOf(tags);
        plan = List.copyOf(plan);
    }

    /** Freshly recorded successful entry. */
    public static PoolEntry success(List<String> tags, String situationSummary, List<String> plan,
            long durationTicks, long now) {
        return new PoolEntry(UUID.randomUUID().toString(), tags, situationSummary, plan,
                Outcome.SUCCESS, null, durationTicks, 0, 1, 0, now, CURRENT_SCHEMA_VERSION);
    }

    /** Freshly recorded failed entry (anti-pattern: the reason teaches as much as a success). */
    public static PoolEntry failure(List<String> tags, String situationSummary, List<String> plan,
            String failureReason, long durationTicks, long now) {
        return new PoolEntry(UUID.randomUUID().toString(), tags, situationSummary, plan,
                Outcome.FAILED, failureReason, durationTicks, 0, 0, 0, now, CURRENT_SCHEMA_VERSION);
    }

    /** Trusted entries rank first in retrieval. */
    public boolean isTrusted(int trustThreshold) {
        return successes >= trustThreshold;
    }

    int tagOverlap(Collection<String> query) {
        int overlap = 0;
        for (String tag : tags) {
            if (query.contains(tag)) {
                overlap++;
            }
        }
        return overlap;
    }

    /** Copy with uses incremented and last-used stamped. */
    PoolEntry used(long now) {
        return new PoolEntry(id, tags, situationSummary, plan, outcome, failureReason,
                durationTicks, uses + 1, successes, now, createdAtGameTime, schemaVersion);
    }

    /** Copy with successes incremented and last-used stamped. */
    PoolEntry recordedSuccess(long now) {
        return new PoolEntry(id, tags, situationSummary, plan, outcome, failureReason,
                durationTicks, uses, successes + 1, now, createdAtGameTime, schemaVersion);
    }
}
