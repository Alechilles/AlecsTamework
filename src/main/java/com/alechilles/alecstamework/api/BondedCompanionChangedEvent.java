package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Compact immutable change notification without a full companion snapshot. */
public record BondedCompanionChangedEvent(
        @Nonnull String profileId,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nullable BondedCompanionState oldState,
        @Nonnull BondedCompanionState newState,
        long revision,
        @Nonnull String reason
) {
    public BondedCompanionChangedEvent {
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = requireText(rosterId, "rosterId");
        newState = Objects.requireNonNull(newState, "newState");
        reason = requireText(reason, "reason");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision cannot be negative.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
