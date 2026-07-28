package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Immutable owner/profile/namespace identity for bonded extension data. */
public record BondedCompanionExtensionDataKey(
        @Nonnull UUID ownerUuid,
        @Nonnull String profileId,
        @Nonnull String namespace
) {
    public BondedCompanionExtensionDataKey {
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        profileId = requireText(profileId, "profileId");
        namespace = requireText(namespace, "namespace");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
