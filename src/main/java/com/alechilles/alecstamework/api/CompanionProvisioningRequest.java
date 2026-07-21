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
    public static final int MAX_CALLER_NAMESPACE_LENGTH = 128;
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 256;
    public static final int MAX_ROLE_ID_LENGTH = 256;
    public static final int MAX_WORLD_NAME_LENGTH = 256;
    public static final int MAX_DISPLAY_NAME_LENGTH = 256;

    public CompanionProvisioningRequest {
        callerNamespace = requireText(callerNamespace, "callerNamespace",
                MAX_CALLER_NAMESPACE_LENGTH);
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey",
                MAX_IDEMPOTENCY_KEY_LENGTH);
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        roleId = requireText(roleId, "roleId", MAX_ROLE_ID_LENGTH);
        disposition = Objects.requireNonNull(disposition, "disposition");
        ownershipWorldName = requireText(ownershipWorldName, "ownershipWorldName",
                MAX_WORLD_NAME_LENGTH);
        displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
        if (displayName != null && displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("displayName exceeds "
                    + MAX_DISPLAY_NAME_LENGTH + " characters.");
        }
        if (destination != null && destination.worldName().length() > MAX_WORLD_NAME_LENGTH) {
            throw new IllegalArgumentException("destination.worldName exceeds "
                    + MAX_WORLD_NAME_LENGTH + " characters.");
        }
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

    private static String requireText(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters.");
        }
        return normalized;
    }
}
