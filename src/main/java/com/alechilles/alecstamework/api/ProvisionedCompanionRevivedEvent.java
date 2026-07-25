package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical revive notification for a provisioned companion. */
public record ProvisionedCompanionRevivedEvent(@Nonnull UUID operationId,
                                               @Nonnull String callerNamespace,
                                               @Nonnull String provisioningKey,
                                               @Nonnull String profileId,
                                               @Nonnull UUID ownerUuid,
                                               @Nonnull String roleId,
                                               @Nullable UUID newNpcUuid,
                                               @Nonnull PopulationCompanionLifecycle lifecycle,
                                               long oldProfileRevision,
                                               long newProfileRevision,
                                               boolean recovered,
                                               long revivedAtMs,
                                               long emittedAtMs) implements TameworkEvent {
    public ProvisionedCompanionRevivedEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        provisioningKey = requireText(provisioningKey, "provisioningKey");
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        roleId = requireText(roleId, "roleId");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        if (oldProfileRevision < 0L || newProfileRevision <= oldProfileRevision) {
            throw new IllegalArgumentException("Revive profile revisions are invalid.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
