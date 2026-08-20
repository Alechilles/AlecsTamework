package com.alechilles.alecstamework.companion.population.domain;

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

    @Nonnull
    PopulationDomainAdmission reserve(
            @Nonnull PopulationDomainReservation reservation
    );

    boolean retireExact(
            @Nonnull OperationId operationId,
            int expectedReservationCount
    );
}
