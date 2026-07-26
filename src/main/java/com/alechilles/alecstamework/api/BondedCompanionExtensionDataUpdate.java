package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable compare-and-set update for namespaced bonded extension data. */
public record BondedCompanionExtensionDataUpdate(
        @Nonnull BondedCompanionExtensionDataKey key,
        @Nonnull String jsonPayload,
        long expectedRevision
) {
    public BondedCompanionExtensionDataUpdate {
        key = Objects.requireNonNull(key, "key");
        jsonPayload = Objects.requireNonNull(jsonPayload, "jsonPayload");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException(
                    "expectedRevision cannot be negative."
            );
        }
    }
}
