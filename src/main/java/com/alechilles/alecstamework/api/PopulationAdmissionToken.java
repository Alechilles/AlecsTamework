package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Opaque capability returned only after a mutation-bound admission is durably prepared. */
public record PopulationAdmissionToken(@Nonnull UUID operationId,
                                       @Nonnull UUID reservationId,
                                       long expiresAtMonotonicNanos,
                                       long settingsRevision,
                                       @Nonnull String providerGenerationToken,
                                       @Nonnull OwnerPopulationCapDecisionViewV2.Readiness readiness) {
    public PopulationAdmissionToken {
        operationId = Objects.requireNonNull(operationId, "operationId");
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        providerGenerationToken = Objects.requireNonNull(providerGenerationToken, "providerGenerationToken").trim();
        readiness = Objects.requireNonNull(readiness, "readiness");
        if (settingsRevision < 0L) {
            throw new IllegalArgumentException("Settings revision cannot be negative.");
        }
        if (providerGenerationToken.isBlank()) {
            throw new IllegalArgumentException("Provider generation token is required.");
        }
    }
}
