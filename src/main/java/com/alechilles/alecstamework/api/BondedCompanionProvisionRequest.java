package com.alechilles.alecstamework.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable idempotent request to provision one bonded profile. */
public record BondedCompanionProvisionRequest(
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nonnull String roleId,
        @Nullable String displayName,
        @Nullable String species,
        @Nullable String gender,
        @Nonnull Map<String, String> snapshotPresentationData,
        long expectedRosterRevision
) {
    public BondedCompanionProvisionRequest {
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = requireText(rosterId, "rosterId");
        roleId = requireText(roleId, "roleId");
        displayName = normalize(displayName);
        species = normalize(species);
        gender = normalize(gender);
        snapshotPresentationData = Map.copyOf(Objects.requireNonNull(
                snapshotPresentationData,
                "snapshotPresentationData"
        ));
        if (expectedRosterRevision < 0L) {
            throw new IllegalArgumentException(
                    "expectedRosterRevision cannot be negative."
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
