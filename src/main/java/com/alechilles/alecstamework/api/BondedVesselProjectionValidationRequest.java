package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Generation-fenced item or live-entity projection validation request. */
public record BondedVesselProjectionValidationRequest(@Nonnull UUID bindingId,
                                                      long generation,
                                                      @Nonnull ProjectionKind projectionKind,
                                                      @Nonnull String projectionFingerprint) {
    public BondedVesselProjectionValidationRequest {
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        projectionKind = Objects.requireNonNull(projectionKind, "projectionKind");
        projectionFingerprint = Objects.requireNonNull(projectionFingerprint, "projectionFingerprint").trim();
        if (generation < 0L) throw new IllegalArgumentException("generation cannot be negative.");
        if (projectionFingerprint.isEmpty()) {
            throw new IllegalArgumentException("projectionFingerprint is required.");
        }
    }

    public enum ProjectionKind {
        ITEM,
        LIVE_ENTITY
    }
}
