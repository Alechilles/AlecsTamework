package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Connection-bound admission authority over canonical lifecycle counts and operation reservations.
 */
public interface OwnerPopulationPort {
    /** Returns committed canonical lifecycle rows in one owner scope. */
    long committedCount(@Nonnull OwnerPopulationScope scope);

    /** Returns positive reservations on nonterminal operation envelopes. */
    long pendingCount(@Nonnull OwnerPopulationScope scope);

    /** Finds exact reservation evidence for one operation and scope. */
    @Nonnull
    Optional<OwnerPopulationReservation> find(
            @Nonnull OperationId operationId,
            @Nonnull OwnerPopulationScope scope
    );

    /** Finds all reservations owned by one shared operation. */
    @Nonnull
    List<OwnerPopulationReservation> findByOperation(
            @Nonnull OperationId operationId
    );

    /** Atomically checks headroom and inserts one idempotent positive reservation. */
    @Nonnull
    OwnerPopulationAdmission reserve(
            @Nonnull OwnerPopulationReservation reservation
    );

    /** Deletes exactly the expected reservations during the canonical durable commit. */
    boolean retireExact(
            @Nonnull OperationId operationId,
            int expectedReservationCount
    );
}

