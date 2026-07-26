package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable namespaced extension payload stored separately from a bonded snapshot. */
public record SqliteBondedCompanionExtensionDataRow(
        @Nonnull String profileId,
        @Nonnull String namespace,
        @Nonnull String jsonPayload,
        long revision,
        long updatedAtMs
) {
    public SqliteBondedCompanionExtensionDataRow {
        profileId = requireText(profileId, "profileId");
        namespace = requireText(namespace, "namespace");
        jsonPayload = requireText(jsonPayload, "jsonPayload");
        if (revision < 0) {
            throw new IllegalArgumentException("Extension revision cannot be negative");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
