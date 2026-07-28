package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable compare-and-set update for namespaced bonded extension data. */
public record BondedCompanionExtensionDataUpdate(
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull BondedCompanionExtensionDataKey key,
        @Nonnull String jsonPayload,
        long expectedRevision
) {
    /** Explicit compare-and-set expectation that no row exists yet. */
    public static final long MISSING_REVISION = -1L;

    public BondedCompanionExtensionDataUpdate {
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        key = Objects.requireNonNull(key, "key");
        jsonPayload = Objects.requireNonNull(jsonPayload, "jsonPayload").trim();
        if (jsonPayload.isEmpty()) {
            throw new IllegalArgumentException("jsonPayload is required.");
        }
        if (expectedRevision < MISSING_REVISION) {
            throw new IllegalArgumentException(
                    "expectedRevision must be MISSING_REVISION or non-negative."
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
}
