package com.alechilles.alecstamework.companion.identity;

import javax.annotation.Nonnull;

/** Durable published evidence that one leased alias became current. */
public record CompanionAliasRotationOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull NpcAlias currentAlias,
        long generation,
        long promotedAtMs
) {
    public CompanionAliasRotationOutcome {
        if (profileId == null || currentAlias == null || generation < 0) {
            throw new IllegalArgumentException("Complete alias rotation outcome is required");
        }
    }
}
