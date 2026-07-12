package com.alechilles.alecstamework.ownership;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable restart evidence for one stable breeding job. */
public record BreedingPopulationReplayState(
        boolean usable,
        @Nullable String attemptKey,
        @Nullable BreedingBirthPlanSnapshot birthPlan,
        @Nonnull Set<String> pendingChildKeys,
        @Nonnull Set<String> committedChildKeys,
        @Nonnull String reason
) {
    public BreedingPopulationReplayState {
        attemptKey = attemptKey == null || attemptKey.isBlank() ? null : attemptKey.trim();
        pendingChildKeys = Set.copyOf(Objects.requireNonNull(
                pendingChildKeys, "pendingChildKeys"
        ));
        committedChildKeys = Set.copyOf(Objects.requireNonNull(
                committedChildKeys, "committedChildKeys"
        ));
        if (!Collections.disjoint(pendingChildKeys, committedChildKeys)) {
            throw new IllegalArgumentException("A child cannot be both pending and committed.");
        }
        reason = Objects.requireNonNull(reason, "reason");
    }

    /** Compatibility shape retained while callers migrate to exact pending-child replay. */
    public BreedingPopulationReplayState(
            boolean usable,
            @Nullable BreedingBirthPlanSnapshot birthPlan,
            @Nonnull Set<String> committedChildKeys,
            @Nonnull String reason
    ) {
        this(usable, null, birthPlan, Set.of(), committedChildKeys, reason);
    }

    public boolean hasPendingChildren() {
        return !pendingChildKeys.isEmpty();
    }

    public boolean hasCommittedChildren() {
        return !committedChildKeys.isEmpty();
    }
}
