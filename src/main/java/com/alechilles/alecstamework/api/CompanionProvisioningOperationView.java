package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable nonterminal-or-terminal view of a durable provisioning journal row. */
public record CompanionProvisioningOperationView(@Nonnull UUID operationId,
                                                 @Nonnull String callerNamespace,
                                                 @Nonnull String idempotencyKey,
                                                 @Nullable UUID correlationId,
                                                 @Nonnull CompanionProvisioningOperationStatus status,
                                                 @Nonnull String reason,
                                                 @Nullable String profileId,
                                                 @Nonnull UUID ownerUuid,
                                                 @Nonnull String roleId,
                                                 @Nullable PopulationCompanionLifecycle lifecycle,
                                                 @Nonnull CompanionProvisioningProjectionStatus projectionStatus,
                                                 long profileRevision,
                                                 boolean recovered,
                                                 long updatedAtMs) {
    public CompanionProvisioningOperationView {
        operationId = Objects.requireNonNull(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        status = Objects.requireNonNull(status, "status");
        reason = requireText(reason, "reason");
        profileId = profileId == null || profileId.isBlank() ? null : profileId.trim();
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        roleId = requireText(roleId, "roleId");
        projectionStatus = Objects.requireNonNull(projectionStatus, "projectionStatus");
        if (profileRevision < -1L) {
            throw new IllegalArgumentException(
                    "Provisioning operation revision is invalid."
            );
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
