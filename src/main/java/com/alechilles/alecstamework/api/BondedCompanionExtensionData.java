package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable namespaced JSON extension payload for one bonded profile. */
public record BondedCompanionExtensionData(
        @Nonnull BondedCompanionExtensionDataKey key,
        @Nonnull String jsonPayload,
        long revision,
        long updatedAtMs
) {
    public BondedCompanionExtensionData {
        key = Objects.requireNonNull(key, "key");
        jsonPayload = Objects.requireNonNull(jsonPayload, "jsonPayload");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision cannot be negative.");
        }
    }
}
