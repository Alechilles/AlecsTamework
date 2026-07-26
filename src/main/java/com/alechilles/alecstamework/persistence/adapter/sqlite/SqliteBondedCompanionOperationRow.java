package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable bounded idempotency record for one bonded request. */
public record SqliteBondedCompanionOperationRow(
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nullable String profileId,
        @Nonnull String operationType,
        @Nonnull String requestHash,
        @Nonnull String operationState,
        @Nullable String resultJson,
        long createdAtMs,
        long updatedAtMs,
        long expiresAtMs
) {
    public SqliteBondedCompanionOperationRow {
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = requireText(rosterId, "rosterId");
        profileId = normalize(profileId);
        operationType = requireText(operationType, "operationType");
        requestHash = requireText(requestHash, "requestHash");
        operationState = requireText(operationState, "operationState");
        resultJson = normalize(resultJson);
        if (requestHash.length() != 64) {
            throw new IllegalArgumentException("requestHash must be a SHA-256 hex value");
        }
        if (!requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be lowercase SHA-256 hex");
        }
        if (expiresAtMs == 0) {
            throw new IllegalArgumentException("Operation retention must be bounded");
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
