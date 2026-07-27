package com.alechilles.alecstamework.persistence.bonded;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable operation identity used to recover a result without current policy. */
public record BondedCompanionOperationProbe(
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nullable String profileId,
        @Nonnull BondedCompanionOperation.Type type,
        @Nullable Long expectedRevision
) {
    public BondedCompanionOperationProbe(
            String callerNamespace,
            String idempotencyKey,
            UUID ownerUuid,
            String rosterId,
            String profileId,
            BondedCompanionOperation.Type type
    ) {
        this(callerNamespace, idempotencyKey, ownerUuid, rosterId, profileId,
                type, null);
    }

    public BondedCompanionOperationProbe {
        callerNamespace = text(callerNamespace, "callerNamespace");
        idempotencyKey = text(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = text(rosterId, "rosterId");
        profileId = profileId == null ? null : text(profileId, "profileId");
        type = Objects.requireNonNull(type, "type");
        if (expectedRevision != null && expectedRevision < 0L) {
            throw new IllegalArgumentException(
                    "expectedRevision cannot be negative");
        }
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
