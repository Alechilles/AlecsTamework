package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable public projection of one canonical bonded-vessel binding. */
public record BondedVesselView(@Nonnull UUID bindingId,
                               @Nonnull String profileId,
                               @Nonnull UUID ownerUuid,
                               @Nonnull String vesselConfigId,
                               @Nonnull BondedVesselState state,
                               long generation,
                               long profileRevision,
                               @Nullable Long cooldownUntilMs,
                               @Nonnull BondedVesselProjectionStatus projectionStatus,
                               @Nullable UUID currentNpcUuid,
                               long updatedAtMs) {
    public BondedVesselView {
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        vesselConfigId = requireText(vesselConfigId, "vesselConfigId");
        state = Objects.requireNonNull(state, "state");
        projectionStatus = Objects.requireNonNull(projectionStatus, "projectionStatus");
        if (generation < 0L || profileRevision < 0L || updatedAtMs < 0L) {
            throw new IllegalArgumentException("Vessel generation, revisions, and update timestamp cannot be negative.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
