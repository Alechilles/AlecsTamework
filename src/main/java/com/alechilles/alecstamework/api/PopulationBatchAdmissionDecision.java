package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Durable preparation outcome for an ordered population admission batch. */
public record PopulationBatchAdmissionDecision(@Nonnull Status status,
                                               @Nonnull String reason,
                                               int requestedUnits,
                                               int admittedUnits,
                                               @Nonnull List<PopulationAdmissionDecision> unitDecisions) {
    public PopulationBatchAdmissionDecision {
        status = Objects.requireNonNull(status, "status");
        reason = Objects.requireNonNull(reason, "reason");
        unitDecisions = List.copyOf(Objects.requireNonNull(unitDecisions, "unitDecisions"));
        if (requestedUnits <= 0 || admittedUnits < 0 || admittedUnits > requestedUnits) {
            throw new IllegalArgumentException("Invalid requested/admitted population batch counts.");
        }
        long accepted = unitDecisions.stream().filter(PopulationAdmissionDecision::accepted).count();
        if (accepted != admittedUnits) {
            throw new IllegalArgumentException("Admitted batch count must match accepted unit decisions.");
        }
        if (status == Status.RESERVED_EXACT && admittedUnits != requestedUnits) {
            throw new IllegalArgumentException("An exact batch must admit every requested unit.");
        }
    }

    @Nonnull
    public static PopulationBatchAdmissionDecision unavailable(int requestedUnits, @Nonnull String reason) {
        return new PopulationBatchAdmissionDecision(Status.UNAVAILABLE, reason, requestedUnits, 0, List.of());
    }

    public enum Status {
        RESERVED_EXACT,
        RESERVED_PARTIAL,
        DENIED,
        UNAVAILABLE
    }
}
