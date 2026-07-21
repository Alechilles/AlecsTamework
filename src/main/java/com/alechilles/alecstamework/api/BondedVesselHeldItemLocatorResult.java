package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Authoritative locator result carrying Tamework-generated exact source evidence. */
public record BondedVesselHeldItemLocatorResult(
        @Nonnull BondedVesselHeldItemProjectionStatus status,
        @Nonnull String reason,
        @Nonnull BondedVesselHeldItemLocatorRequest request,
        @Nullable BondedVesselSourceItemEvidence sourceEvidence,
        @Nullable BondedVesselView vessel,
        boolean authoritative) {
    public BondedVesselHeldItemLocatorResult {
        status = Objects.requireNonNull(status, "status");
        reason = requireText(reason, "reason");
        request = Objects.requireNonNull(request, "request");
        if (status == BondedVesselHeldItemProjectionStatus.VALID) {
            if (!authoritative || sourceEvidence == null || vessel == null) {
                throw new IllegalArgumentException("VALID locator results require evidence and vessel authority.");
            }
            if (!request.actorUuid().equals(vessel.ownerUuid())
                    || request.requiredState() != vessel.state()
                    || vessel.projectionStatus() != BondedVesselProjectionStatus.PRESENT) {
                throw new IllegalArgumentException("VALID locator result conflicts with canonical vessel state.");
            }
            if (!request.holderEvidenceId().equals(sourceEvidence.holderEvidenceId())
                    || !request.containerPath().equals(sourceEvidence.containerPath())
                    || request.inventorySlot() != sourceEvidence.inventorySlot()
                    || (request.expectedItemId() != null
                    && !request.expectedItemId().equals(sourceEvidence.itemId()))) {
                throw new IllegalArgumentException("VALID locator result conflicts with requested item location.");
            }
        } else if (authoritative) {
            throw new IllegalArgumentException("Only VALID locator results may be authoritative.");
        }
    }

    public Optional<BondedVesselSourceItemEvidence> resolvedSourceEvidence() {
        return Optional.ofNullable(sourceEvidence);
    }

    public Optional<BondedVesselView> resolvedVessel() {
        return Optional.ofNullable(vessel);
    }

    public static BondedVesselHeldItemLocatorResult unavailable(
            BondedVesselHeldItemLocatorRequest request) {
        return new BondedVesselHeldItemLocatorResult(
                BondedVesselHeldItemProjectionStatus.UNAVAILABLE,
                "bonded-vessel-held-item-locator-unavailable",
                Objects.requireNonNull(request, "request"), null, null, false);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
