package com.alechilles.alecstamework.persistence.bonded;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable idempotency envelope for one atomic bonded mutation. */
public record BondedCompanionOperation(
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull String requestHash,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nullable String profileId,
        @Nonnull Type type,
        long attemptedAtMs,
        long retainedUntilMs,
        @Nullable StoreLeaseIdentity storeLeaseIdentity
) {
    /** Finite bonded mutation vocabulary persisted by the adapter. */
    public enum Type { CAPTURE, PROVISION, STORE, REVIVE }

    public BondedCompanionOperation(
            String callerNamespace,
            String idempotencyKey,
            String requestHash,
            UUID ownerUuid,
            String rosterId,
            String profileId,
            Type type,
            long attemptedAtMs,
            long retainedUntilMs
    ) {
        this(callerNamespace, idempotencyKey, requestHash, ownerUuid, rosterId,
                profileId, type, attemptedAtMs, retainedUntilMs, null);
    }

    public BondedCompanionOperation {
        callerNamespace = text(callerNamespace, "callerNamespace");
        idempotencyKey = text(idempotencyKey, "idempotencyKey");
        requestHash = text(requestHash, "requestHash").toLowerCase(Locale.ROOT);
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = text(rosterId, "rosterId");
        profileId = optional(profileId);
        type = Objects.requireNonNull(type, "type");
        if (!requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be SHA-256 hex");
        }
        if (retainedUntilMs == 0) {
            throw new IllegalArgumentException("retainedUntilMs must be bounded");
        }
        if (storeLeaseIdentity != null && type != Type.STORE) {
            throw new IllegalArgumentException(
                    "only STORE operations carry exact lease identity");
        }
    }

    /** Stable DB-authored projection identity bound into one STORE request. */
    public record StoreLeaseIdentity(
            @Nonnull String leaseToken,
            @Nonnull UUID liveNpcUuid,
            @Nonnull String worldKey
    ) {
        public StoreLeaseIdentity {
            leaseToken = text(leaseToken, "leaseToken");
            liveNpcUuid = Objects.requireNonNull(liveNpcUuid, "liveNpcUuid");
            worldKey = text(worldKey, "worldKey");
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
