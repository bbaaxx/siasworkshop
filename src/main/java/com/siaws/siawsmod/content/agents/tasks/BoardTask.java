package com.siaws.siawsmod.content.agents.tasks;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * One entry of the shared task board (design pillar 4). Comfort detectors raise singleton
 * deficit tasks (keyed, so two detectors can't raise two copies of the same deficit); agents
 * claim them with a lease so a stalled agent releases its task automatically. Immutable;
 * {@link TaskBoard} transitions replace the record with a copy.
 *
 * @param id                unique id (UUID string)
 * @param key               singleton key, e.g. {@code comfort:light:<playerUuid>}
 * @param type              goal family; BUILD reserved for the build queue
 * @param status            OPEN / CLAIMED / DONE / FAILED
 * @param claimantUuid      agent that claimed the task, null while OPEN
 * @param leaseUntilGameTime claim is valid while {@code now < leaseUntilGameTime}; at
 *                           {@code now == leaseUntilGameTime} the lease is spent and the task
 *                           may be stolen or swept. 0 = no lease
 * @param createdAtGameTime  game time the task was raised
 * @param resolvedAtGameTime game time the task left CLAIMED, 0 while unresolved
 * @param detail            short one-line situation, e.g. deficit location
 * @param failureReason     null unless FAILED
 */
public record BoardTask(
        String id,
        String key,
        Type type,
        Status status,
        @Nullable UUID claimantUuid,
        long leaseUntilGameTime,
        long createdAtGameTime,
        long resolvedAtGameTime,
        String detail,
        @Nullable String failureReason) {

    public enum Type {
        COMFORT_LIGHT,
        COMFORT_SHELTER,
        BUILD
    }

    public enum Status {
        OPEN,
        CLAIMED,
        DONE,
        FAILED
    }

    /** True while the task still needs work (not DONE/FAILED). */
    public boolean isActive() {
        return status == Status.OPEN || status == Status.CLAIMED;
    }

    /** Pool tags derived from the type, e.g. COMFORT_LIGHT → [comfort, light]. */
    public List<String> poolTags() {
        return switch (type) {
            case COMFORT_LIGHT -> List.of("comfort", "light");
            case COMFORT_SHELTER -> List.of("comfort", "shelter");
            case BUILD -> List.of("build");
        };
    }

    /** Canonical fix pattern recorded as the pool plan when this task type resolves. */
    public List<String> poolPlan() {
        return switch (type) {
            case COMFORT_LIGHT -> List.of("light()");
            case COMFORT_SHELTER -> List.of("place(block)", "use(item)");
            case BUILD -> List.of("build(blueprint)");
        };
    }

    BoardTask transitioned(Status newStatus, @Nullable UUID claimant, long leaseUntil, long resolvedAt,
            @Nullable String failureReason) {
        return new BoardTask(id, key, type, newStatus, claimant, leaseUntil, createdAtGameTime,
                resolvedAt, detail, failureReason);
    }
}
