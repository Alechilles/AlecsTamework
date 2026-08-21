package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One eligible companion contribution in a combat defeat projection. */
public record CombatContributionView(
        @Nonnull UUID companionId,
        @Nullable UUID ownerId,
        double contribution
) {
    public CombatContributionView {
        companionId = Objects.requireNonNull(companionId, "companionId");
        if (!Double.isFinite(contribution) || contribution < 0.0) {
            throw new IllegalArgumentException("contribution must be finite and non-negative.");
        }
    }
}
