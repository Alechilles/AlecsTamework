package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable idempotent request to summon or store one bonded companion. */
public record BondedCompanionActionRequest(
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nonnull String profileId,
        long expectedRevision,
        @Nullable String worldKey,
        @Nullable BondedCompanionActionContext actionContext
) {
    public BondedCompanionActionRequest(
            String callerNamespace, String idempotencyKey, UUID ownerUuid,
            String rosterId, String profileId, long expectedRevision,
            String worldKey
    ) {
        this(callerNamespace, idempotencyKey, ownerUuid, rosterId, profileId,
                expectedRevision, worldKey, null);
    }

    public BondedCompanionActionRequest {
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = requireText(rosterId, "rosterId");
        profileId = requireText(profileId, "profileId");
        worldKey = normalize(worldKey);
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException(
                    "expectedRevision cannot be negative."
            );
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
