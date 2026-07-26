package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable bounded cleanup intent for one exact source or projection UUID. */
public record SqliteBondedCompanionCleanupRow(
        @Nonnull String cleanupId,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nonnull String profileId,
        @Nullable String leaseToken,
        @Nonnull String targetKind,
        @Nonnull UUID targetNpcUuid,
        @Nonnull String worldKey,
        @Nonnull String cleanupReason,
        @Nonnull String cleanupState,
        int attemptCount,
        long nextAttemptAtMs,
        long createdAtMs,
        long retainedUntilMs
) {
    public SqliteBondedCompanionCleanupRow {
        cleanupId = requireText(cleanupId, "cleanupId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = requireText(rosterId, "rosterId");
        profileId = requireText(profileId, "profileId");
        leaseToken = normalize(leaseToken);
        targetKind = requireText(targetKind, "targetKind");
        targetNpcUuid = Objects.requireNonNull(targetNpcUuid, "targetNpcUuid");
        worldKey = requireText(worldKey, "worldKey");
        cleanupReason = requireText(cleanupReason, "cleanupReason");
        cleanupState = requireText(cleanupState, "cleanupState");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("Cleanup attempt count cannot be negative");
        }
        if (retainedUntilMs == 0) {
            throw new IllegalArgumentException("Cleanup retention must be bounded");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
