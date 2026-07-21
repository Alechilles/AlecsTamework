package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable canonical lookup for a companion created through provisioning. */
public record ProvisionedCompanionView(@Nonnull UUID provisioningOperationId,
                                       @Nonnull String callerNamespace,
                                       @Nonnull String idempotencyKey,
                                       @Nonnull String profileId,
                                       @Nonnull UUID ownerUuid,
                                       @Nonnull String roleId,
                                       @Nonnull PopulationCompanionLifecycle lifecycle,
                                       @Nonnull CompanionProvisioningProjectionStatus projectionStatus,
                                       @Nullable UUID currentNpcUuid,
                                       long profileRevision,
                                       long updatedAtMs) {
    public ProvisionedCompanionView {
        provisioningOperationId = Objects.requireNonNull(provisioningOperationId, "provisioningOperationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        roleId = requireText(roleId, "roleId");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        projectionStatus = Objects.requireNonNull(projectionStatus, "projectionStatus");
        if (profileRevision < 0L || updatedAtMs < 0L) {
            throw new IllegalArgumentException("Provisioned profile revision and update timestamp cannot be negative.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
