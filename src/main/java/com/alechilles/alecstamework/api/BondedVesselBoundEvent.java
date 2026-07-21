package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Post-commit notification that a profile received its first vessel binding. */
public record BondedVesselBoundEvent(@Nonnull UUID operationId,
                                     @Nonnull UUID bindingId,
                                     @Nonnull String profileId,
                                     @Nonnull UUID ownerUuid,
                                     @Nonnull String vesselConfigId,
                                     long generation,
                                     long profileRevision,
                                     @Nonnull BondedVesselState state,
                                     boolean recovered,
                                     long boundAtMs,
                                     long emittedAtMs) implements TameworkEvent {
    public BondedVesselBoundEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        vesselConfigId = requireText(vesselConfigId, "vesselConfigId");
        state = Objects.requireNonNull(state, "state");
        if (generation <= 0L || profileRevision < 0L) {
            throw new IllegalArgumentException("Binding generation and profile revision cannot be negative.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
