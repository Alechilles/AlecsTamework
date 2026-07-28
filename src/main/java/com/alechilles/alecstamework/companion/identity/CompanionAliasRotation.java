package com.alechilles.alecstamework.companion.identity;

import javax.annotation.Nonnull;

/** Stable profile and target runtime UUID for one fenced alias rotation. */
public record CompanionAliasRotation(
        @Nonnull ProfileId profileId,
        @Nonnull NpcAlias targetAlias,
        long requestedAtMs
) {
    public CompanionAliasRotation {
        if (profileId == null || targetAlias == null) {
            throw new IllegalArgumentException(
                    "Alias rotation profile and target are required"
            );
        }
    }
}
