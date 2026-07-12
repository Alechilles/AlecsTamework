package com.alechilles.alecstamework.ownership;

import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable restart evidence for one stable breeding job. */
public record BreedingPopulationReplayState(
        boolean usable,
        @Nullable BreedingBirthPlanSnapshot birthPlan,
        @Nonnull Set<String> committedChildKeys,
        @Nonnull String reason
) {
    public BreedingPopulationReplayState {
        committedChildKeys = Set.copyOf(Objects.requireNonNull(
                committedChildKeys, "committedChildKeys"
        ));
        reason = Objects.requireNonNull(reason, "reason");
    }

    public boolean hasCommittedChildren() {
        return !committedChildKeys.isEmpty();
    }
}
