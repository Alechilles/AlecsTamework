package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Connection-bound authority for weighted named-domain usage and reservations. */
public interface PopulationDomainPort {
    @Nonnull
    PopulationDomainCounts counts(@Nonnull PopulationDomainBucket bucket);

    @Nonnull
    Optional<PopulationDomainReservation> find(
            @Nonnull OperationId operationId,
            @Nonnull PopulationDomainBucket bucket
    );

    @Nonnull
    List<PopulationDomainReservation> findByOperation(
            @Nonnull OperationId operationId
    );

    /** Reads one exact phase-aware profile evidence set on the current connection. */
    @Nonnull
    ProfileEvidence profileEvidence(
            @Nonnull ProfileId profileId,
            @Nullable OperationId currentOperationId
    );

    @Nonnull
    PopulationDomainAdmission reserve(
            @Nonnull PopulationDomainReservation reservation
    );

    boolean retireExact(
            @Nonnull OperationId operationId,
            int expectedReservationCount
    );

    /** Applies one frozen source-row convergence plan with exact old-value fences. */
    boolean convergeExact(@Nonnull PopulationDomainConvergencePlan plan);

    /**
     * Complete rows for one profile, split by operation phase and current operation identity.
     * Pending rows from every other operation remain visible so callers can fail closed.
     */
    record ProfileEvidence(
            @Nonnull List<PopulationDomainReservation> committed,
            @Nonnull List<PopulationDomainReservation> currentOperationPending,
            @Nonnull List<PopulationDomainReservation> foreignPending
    ) {
        public ProfileEvidence {
            if (committed == null || currentOperationPending == null
                    || foreignPending == null
                    || committed.stream().anyMatch(java.util.Objects::isNull)
                    || currentOperationPending.stream().anyMatch(java.util.Objects::isNull)
                    || foreignPending.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Complete profile evidence is required");
            }
            committed = List.copyOf(committed);
            currentOperationPending = List.copyOf(currentOperationPending);
            foreignPending = List.copyOf(foreignPending);
        }
    }
}
