package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Exact held-item lookup request used before constructing a generation-fenced transition. */
public record BondedVesselHeldItemProjectionRequest(@Nonnull UUID actorUuid,
                                                    @Nonnull BondedVesselSourceItemEvidence sourceEvidence,
                                                    @Nonnull BondedVesselState requiredState) {
    public BondedVesselHeldItemProjectionRequest {
        actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        sourceEvidence = Objects.requireNonNull(sourceEvidence, "sourceEvidence");
        requiredState = Objects.requireNonNull(requiredState, "requiredState");
        if (requiredState == BondedVesselState.RELEASED) {
            throw new IllegalArgumentException("A released binding cannot have an authoritative held item.");
        }
    }
}
