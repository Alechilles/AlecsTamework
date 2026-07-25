package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical death notification for a provisioned companion, independent of command links. */
public record ProvisionedCompanionDeathRecordedEvent(@Nonnull UUID operationId,
                                                     @Nonnull String callerNamespace,
                                                     @Nonnull String provisioningKey,
                                                     @Nonnull String profileId,
                                                     @Nonnull UUID ownerUuid,
                                                     @Nonnull String roleId,
                                                     @Nonnull PopulationCompanionLifecycle lifecycle,
                                                     @Nonnull CompanionProvisioningProjectionStatus projectionStatus,
                                                     @Nullable UUID lastNpcUuid,
                                                     long oldProfileRevision,
                                                     long newProfileRevision,
                                                     boolean recovered,
                                                     long diedAtMs,
                                                     long emittedAtMs) implements TameworkEvent {
    public ProvisionedCompanionDeathRecordedEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        provisioningKey = requireText(provisioningKey, "provisioningKey");
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        roleId = requireText(roleId, "roleId");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        projectionStatus = Objects.requireNonNull(
                projectionStatus, "projectionStatus"
        );
        if (lifecycle != PopulationCompanionLifecycle.DEAD_REVIVABLE
                || projectionStatus
                != CompanionProvisioningProjectionStatus.UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "Death event requires unavailable dead lifecycle."
            );
        }
        if (oldProfileRevision < 0L || newProfileRevision <= oldProfileRevision) {
            throw new IllegalArgumentException("Death profile revisions are invalid.");
        }
    }

    /** Compatibility constructor for the original v0.9 event shape. */
    public ProvisionedCompanionDeathRecordedEvent(
            @Nonnull UUID operationId,
            @Nonnull String callerNamespace,
            @Nonnull String provisioningKey,
            @Nonnull String profileId,
            @Nonnull UUID ownerUuid,
            @Nonnull String roleId,
            @Nullable UUID lastNpcUuid,
            long oldProfileRevision,
            long newProfileRevision,
            boolean recovered,
            long diedAtMs,
            long emittedAtMs
    ) {
        this(
                operationId,
                callerNamespace,
                provisioningKey,
                profileId,
                ownerUuid,
                roleId,
                PopulationCompanionLifecycle.DEAD_REVIVABLE,
                CompanionProvisioningProjectionStatus.UNAVAILABLE,
                lastNpcUuid,
                oldProfileRevision,
                newProfileRevision,
                recovered,
                diedAtMs,
                emittedAtMs
        );
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
