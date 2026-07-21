package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Opaque capability for a durably prepared bonded-vessel transition. */
public record BondedVesselTransitionToken(@Nonnull UUID operationId,
                                          @Nonnull UUID reservationId,
                                          @Nonnull UUID bindingId,
                                          @Nonnull BondedVesselTransition transition,
                                          @Nonnull BondedVesselState sourceState,
                                          @Nonnull BondedVesselState candidateState,
                                          @Nonnull String sourceItemFingerprint,
                                          @Nonnull String candidateItemId,
                                          @Nonnull String candidateItemFingerprint,
                                          @Nullable PopulationAdmissionLocation destination,
                                          long expectedGeneration,
                                          long candidateGeneration,
                                          long expectedProfileRevision,
                                          long expiresAtMonotonicNanos) {
    public BondedVesselTransitionToken {
        operationId = Objects.requireNonNull(operationId, "operationId");
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        transition = Objects.requireNonNull(transition, "transition");
        sourceState = Objects.requireNonNull(sourceState, "sourceState");
        candidateState = Objects.requireNonNull(candidateState, "candidateState");
        sourceItemFingerprint = requireText(sourceItemFingerprint, "sourceItemFingerprint");
        candidateItemId = requireText(candidateItemId, "candidateItemId");
        candidateItemFingerprint = requireText(candidateItemFingerprint, "candidateItemFingerprint");
        if (expectedGeneration < 0L || expectedGeneration == Long.MAX_VALUE
                || candidateGeneration != expectedGeneration + 1L
                || expectedProfileRevision < 0L) {
            throw new IllegalArgumentException("Bonded-vessel token generations or revision are invalid.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
