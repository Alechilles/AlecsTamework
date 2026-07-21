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
    public static final int MAX_CALLER_NAMESPACE_LENGTH = 128;
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 256;
    public static final int MAX_PROFILE_ID_LENGTH = 256;
    public static final int MAX_WORLD_NAME_LENGTH = 256;

    public ProvisionedCompanionTransitionRequest {
        callerNamespace = requireText(callerNamespace, "callerNamespace",
                MAX_CALLER_NAMESPACE_LENGTH);
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey",
                MAX_IDEMPOTENCY_KEY_LENGTH);
        actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        profileId = requireText(profileId, "profileId", MAX_PROFILE_ID_LENGTH);
        transition = Objects.requireNonNull(transition, "transition");
        ownershipWorldName = requireText(ownershipWorldName, "ownershipWorldName",
                MAX_WORLD_NAME_LENGTH);
        if (destination != null && destination.worldName().length() > MAX_WORLD_NAME_LENGTH) {
            throw new IllegalArgumentException("destination.worldName exceeds "
                    + MAX_WORLD_NAME_LENGTH + " characters.");
        }
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

    private static String requireText(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters.");
        }
        return normalized;
    }
}
