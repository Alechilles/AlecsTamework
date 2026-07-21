package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Post-commit notification for one idempotently provisioned companion profile. */
public record CompanionProvisionedEvent(@Nonnull UUID operationId,
                                        @Nonnull String callerNamespace,
                                        @Nonnull String idempotencyKey,
                                        @Nonnull String profileId,
                                        @Nonnull UUID ownerUuid,
                                        @Nonnull String roleId,
                                        @Nonnull PopulationCompanionLifecycle lifecycle,
                                        @Nonnull CompanionProvisioningProjectionStatus projectionStatus,
                                        long profileRevision,
                                        boolean recovered,
                                        long provisionedAtMs,
                                        long emittedAtMs) implements TameworkEvent {
    public CompanionProvisionedEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        roleId = requireText(roleId, "roleId");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        projectionStatus = Objects.requireNonNull(projectionStatus, "projectionStatus");
        if (profileRevision < 0L) throw new IllegalArgumentException("Profile revision cannot be negative.");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
