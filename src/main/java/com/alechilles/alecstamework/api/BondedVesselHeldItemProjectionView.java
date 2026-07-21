package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bounded public result for a held bonded item. Only {@link #authoritative()} results may seed a
 * transition request; diagnostic vessel data on a denial never grants mutation authority.
 */
public record BondedVesselHeldItemProjectionView(
        @Nonnull BondedVesselHeldItemProjectionStatus status,
        @Nonnull String reason,
        @Nonnull BondedVesselHeldItemProjectionRequest request,
        @Nullable BondedVesselView vessel,
        boolean authoritative) {
    private static final int REASON_MAX_LENGTH = 512;

    public BondedVesselHeldItemProjectionView {
        status = Objects.requireNonNull(status, "status");
        reason = requireText(reason, "reason", REASON_MAX_LENGTH);
        request = Objects.requireNonNull(request, "request");
        if (status == BondedVesselHeldItemProjectionStatus.VALID) {
            if (!authoritative || vessel == null) {
                throw new IllegalArgumentException("VALID held-item projections require authoritative vessel data.");
            }
            if (!request.actorUuid().equals(vessel.ownerUuid())) {
                throw new IllegalArgumentException("A VALID held-item projection must match the canonical owner.");
            }
            if (request.requiredState() != vessel.state()) {
                throw new IllegalArgumentException("A VALID held-item projection must match the required state.");
            }
            if (vessel.projectionStatus() != BondedVesselProjectionStatus.PRESENT) {
                throw new IllegalArgumentException("A VALID held-item projection must be durably present.");
            }
        } else if (authoritative) {
            throw new IllegalArgumentException("Only VALID held-item projections may be authoritative.");
        }
    }

    @Nonnull
    public Optional<BondedVesselView> resolvedVessel() {
        return Optional.ofNullable(vessel);
    }

    @Nonnull
    public static BondedVesselHeldItemProjectionView unavailable(
            @Nonnull BondedVesselHeldItemProjectionRequest request
    ) {
        return new BondedVesselHeldItemProjectionView(
                BondedVesselHeldItemProjectionStatus.UNAVAILABLE,
                "bonded-vessel-held-item-resolution-unavailable",
                Objects.requireNonNull(request, "request"),
                null,
                false
        );
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters.");
        }
        return normalized;
    }
}
