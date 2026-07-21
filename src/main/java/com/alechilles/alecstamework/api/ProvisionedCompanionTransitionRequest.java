package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Idempotent request to activate or revive one previously provisioned profile. */
public record ProvisionedCompanionTransitionRequest(@Nonnull String callerNamespace,
                                                    @Nonnull String idempotencyKey,
                                                    @Nonnull UUID actorUuid,
                                                    @Nonnull String profileId,
                                                    long expectedProfileRevision,
                                                    @Nonnull ProvisionedCompanionTransition transition,
                                                    @Nonnull String ownershipWorldName,
                                                    @Nullable PopulationAdmissionLocation destination) {
    public ProvisionedCompanionTransitionRequest {
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        profileId = requireText(profileId, "profileId");
        transition = Objects.requireNonNull(transition, "transition");
        ownershipWorldName = requireText(ownershipWorldName, "ownershipWorldName");
        if (expectedProfileRevision < 0L) {
            throw new IllegalArgumentException("Expected profile revision cannot be negative.");
        }
        if ((transition == ProvisionedCompanionTransition.ACTIVATE
                || transition == ProvisionedCompanionTransition.REVIVE_ACTIVE) && destination == null) {
            throw new IllegalArgumentException("Active projection requires a destination.");
        }
        if (transition == ProvisionedCompanionTransition.REVIVE_DORMANT && destination != null) {
            throw new IllegalArgumentException("Dormant revive cannot declare an active destination.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
