package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable entity and optional owner identity used by combat activities. */
public record CombatParticipantView(
        @Nonnull UUID entityId,
        @Nullable UUID ownerId
) {
    public CombatParticipantView {
        entityId = Objects.requireNonNull(entityId, "entityId");
    }
}
