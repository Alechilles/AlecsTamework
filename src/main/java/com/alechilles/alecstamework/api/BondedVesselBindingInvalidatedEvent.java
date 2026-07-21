package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Post-commit notification that a binding projection was invalidated or quarantined. */
public record BondedVesselBindingInvalidatedEvent(@Nonnull UUID operationId,
                                                  @Nonnull UUID bindingId,
                                                  @Nonnull String profileId,
                                                  @Nonnull UUID ownerUuid,
                                                  @Nonnull String vesselConfigId,
                                                  long oldGeneration,
                                                  long newGeneration,
                                                  @Nonnull BondedVesselState state,
                                                  @Nonnull BondedVesselProjectionStatus projectionStatus,
                                                  @Nonnull String reason,
                                                  boolean recovered,
                                                  long invalidatedAtMs,
                                                  long emittedAtMs) implements TameworkEvent {
    public BondedVesselBindingInvalidatedEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        vesselConfigId = requireText(vesselConfigId, "vesselConfigId");
        state = Objects.requireNonNull(state, "state");
        projectionStatus = Objects.requireNonNull(projectionStatus, "projectionStatus");
        reason = requireText(reason, "reason");
        if (oldGeneration < 0L || newGeneration <= oldGeneration) {
            throw new IllegalArgumentException("Invalidated binding generations are invalid.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
