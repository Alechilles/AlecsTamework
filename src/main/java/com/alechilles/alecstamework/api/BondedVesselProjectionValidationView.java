package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Result of validating a vessel projection against canonical binding state. */
public record BondedVesselProjectionValidationView(@Nonnull UUID bindingId,
                                                   @Nonnull BondedVesselProjectionValidationStatus status,
                                                   @Nonnull String reason,
                                                   long canonicalGeneration,
                                                   boolean authoritative) {
    public static final long UNKNOWN_GENERATION = -1L;

    public BondedVesselProjectionValidationView {
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        status = Objects.requireNonNull(status, "status");
        reason = Objects.requireNonNull(reason, "reason").trim();
        if (reason.isEmpty()) throw new IllegalArgumentException("reason is required.");
        if (canonicalGeneration < UNKNOWN_GENERATION) {
            throw new IllegalArgumentException("canonicalGeneration must be -1 or non-negative.");
        }
    }

    public static BondedVesselProjectionValidationView unavailable(UUID bindingId) {
        return new BondedVesselProjectionValidationView(
                bindingId,
                BondedVesselProjectionValidationStatus.UNKNOWN,
                "bonded-vessel-authority-unavailable",
                UNKNOWN_GENERATION,
                false
        );
    }
}
