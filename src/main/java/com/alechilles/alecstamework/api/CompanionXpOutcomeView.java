package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable companion progression outcome attached to a committed activity. */
public record CompanionXpOutcomeView(
        @Nonnull UUID companionId,
        @Nullable UUID ownerId,
        @Nonnull CompanionXpSource source,
        double awardedXp,
        int previousLevel,
        int currentLevel,
        double previousTotalXp,
        double currentTotalXp,
        boolean leveledUp
) {
    public CompanionXpOutcomeView {
        companionId = Objects.requireNonNull(companionId, "companionId");
        source = Objects.requireNonNull(source, "source");
        if (!Double.isFinite(awardedXp) || awardedXp < 0.0
                || !Double.isFinite(previousTotalXp)
                || !Double.isFinite(currentTotalXp)
                || previousLevel < 0 || currentLevel < 0) {
            throw new IllegalArgumentException("Invalid companion XP outcome.");
        }
    }

    /** Minimal outcome constructor for producers without level snapshots. */
    public CompanionXpOutcomeView(
            @Nonnull UUID companionId,
            @Nullable UUID ownerId,
            @Nonnull CompanionXpSource source,
            double awardedXp
    ) {
        this(companionId, ownerId, source, awardedXp, 0, 0, 0.0, 0.0, false);
    }
}
