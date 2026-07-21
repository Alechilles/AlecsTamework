package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Idempotent request to create one generic canonical companion profile. */
public record CompanionProvisioningRequest(@Nonnull String callerNamespace,
                                           @Nonnull String idempotencyKey,
                                           @Nullable UUID correlationId,
                                           @Nonnull UUID ownerUuid,
                                           @Nonnull String roleId,
                                           @Nonnull CompanionProvisioningDisposition disposition,
                                           @Nonnull String ownershipWorldName,
                                           @Nullable PopulationAdmissionLocation destination,
                                           @Nullable String displayName,
                                           @Nullable Vector3View homePosition,
                                           long expectedPolicyRevision) {
    public static final long CURRENT_POLICY_REVISION = -1L;

    public CompanionProvisioningRequest {
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        roleId = requireText(roleId, "roleId");
        disposition = Objects.requireNonNull(disposition, "disposition");
        ownershipWorldName = requireText(ownershipWorldName, "ownershipWorldName");
        displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
        if (homePosition != null && (!Double.isFinite(homePosition.x())
                || !Double.isFinite(homePosition.y()) || !Double.isFinite(homePosition.z()))) {
            throw new IllegalArgumentException("Home position coordinates must be finite.");
        }
        if (disposition == CompanionProvisioningDisposition.ACTIVE && destination == null) {
            throw new IllegalArgumentException("Active provisioning requires a destination.");
        }
        if (disposition == CompanionProvisioningDisposition.PROVISIONED_DORMANT && destination != null) {
            throw new IllegalArgumentException("Dormant provisioning cannot declare an active destination.");
        }
        if (expectedPolicyRevision < CURRENT_POLICY_REVISION) {
            throw new IllegalArgumentException("Expected policy revision must be -1 or non-negative.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
