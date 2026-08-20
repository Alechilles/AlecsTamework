package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

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

    /** Returns every committed row retained by one profile. */
    @Nonnull
    List<PopulationDomainReservation> findCommittedByProfile(
            @Nonnull ProfileId profileId
    );

    /** Returns committed rows for one profile and one complete bucket. */
    @Nonnull
    List<PopulationDomainReservation> findCommittedByProfileAndBucket(
            @Nonnull ProfileId profileId,
            @Nonnull PopulationDomainBucket bucket
    );

    /** Returns pending rows for one profile and bucket. */
    @Nonnull
    List<PopulationDomainReservation> findPendingByProfileAndBucket(
            @Nonnull ProfileId profileId,
            @Nonnull PopulationDomainBucket bucket
    );

    /** Returns all non-committed rows retained by one profile. */
    @Nonnull
    List<PopulationDomainReservation> findPendingByProfile(
            @Nonnull ProfileId profileId
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
}
