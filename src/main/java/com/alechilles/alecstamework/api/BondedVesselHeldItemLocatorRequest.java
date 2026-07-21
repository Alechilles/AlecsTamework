package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Location-only held-item lookup; Tamework supplies revision and fingerprint evidence. */
public record BondedVesselHeldItemLocatorRequest(
        @Nonnull UUID actorUuid,
        @Nonnull String holderEvidenceId,
        @Nonnull String containerPath,
        int inventorySlot,
        @Nullable String expectedItemId,
        @Nonnull BondedVesselState requiredState) {
    public BondedVesselHeldItemLocatorRequest {
        actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        holderEvidenceId = requireText(holderEvidenceId, "holderEvidenceId");
        containerPath = requireText(containerPath, "containerPath");
        expectedItemId = expectedItemId == null || expectedItemId.isBlank()
                ? null : expectedItemId.trim();
        requiredState = Objects.requireNonNull(requiredState, "requiredState");
        if (inventorySlot < 0) throw new IllegalArgumentException("inventorySlot cannot be negative.");
        if (requiredState == BondedVesselState.RELEASED) {
            throw new IllegalArgumentException("A released binding cannot have an authoritative held item.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
