package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmission;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReservation;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Reusable population participant in the one shared prepare/durable operation protocol.
 */
public final class SqliteOwnerPopulationParticipant
        implements PreparedOperationDetail {
    private final OwnerPopulationAdmissionPlan plan;
    private final int units;

    public SqliteOwnerPopulationParticipant(
            @Nonnull OwnerPopulationAdmissionPlan plan
    ) {
        this(plan, 1);
    }

    /** Creates one owner participant for an aggregate admission. */
    public SqliteOwnerPopulationParticipant(
            @Nonnull OwnerPopulationAdmissionPlan plan,
            int units
    ) {
        if (plan == null) {
            throw new IllegalArgumentException(
                    "Owner population admission plan is required"
            );
        }
        if (units <= 0) {
            throw new IllegalArgumentException("Admission units must be positive");
        }
        this.plan = plan;
        this.units = units;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        requireEnvelope(operation);
        for (OwnerPopulationReservation reservation : reservations(operation)) {
            OwnerPopulationAdmission result =
                    transaction.population().reserve(reservation);
            if (!result.admitted()) {
                throw new IllegalStateException(
                        "owner_population_"
                                + result.status().name().toLowerCase()
                );
            }
        }
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        requireEnvelope(operation);
        List<OwnerPopulationReservation> actual =
                transaction.population()
                        .findByOperation(operation.operationId());
        if (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED
                || operation.phase() == OperationPhase.COMPENSATED
                || operation.phase() == OperationPhase.FAILED) {
            return actual.isEmpty();
        }
        return actual.equals(reservations(operation));
    }

    /**
     * Retires exact reservation evidence in the same transaction as canonical durable work.
     */
    @Nonnull
    public DurableOperationWork decorate(
            @Nonnull DurableOperationWork durableWork
    ) {
        if (durableWork == null) {
            throw new IllegalArgumentException(
                    "Durable operation work is required"
            );
        }
        return (transaction, operation) -> {
            if (!matches(transaction, operation)) {
                throw new IllegalStateException(
                        "owner_population_reservation_missing"
                );
            }
            var events = durableWork.execute(transaction, operation);
            if (!transaction.population().retireExact(
                    operation.operationId(),
                    plan.increases().size()
            )) {
                throw new IllegalStateException(
                        "owner_population_reservation_retirement_failed"
                );
            }
            return events;
        };
    }

    /** Retires only this participant's exact prepared reservation set. */
    public void retirePrepared(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (!transaction.population().retireExact(
                operation.operationId(), plan.increases().size()
        )) {
            throw new IllegalStateException(
                    "owner_population_reservation_retirement_failed"
            );
        }
    }

    List<OwnerPopulationReservation> reservations(
            OperationEnvelope operation
    ) {
        return plan.increases().stream()
                .map(increase -> new OwnerPopulationReservation(
                        operation.operationId(),
                        plan.profileId(),
                        plan.expectedLifecycleRevision(),
                        increase.scope(),
                        Math.multiplyExact(increase.capacityDelta(), units),
                        increase.snapshottedLimit(),
                        operation.createdAtMs()
                ))
                .toList();
    }

    private void requireEnvelope(OperationEnvelope operation) {
        if (operation == null
                || !java.util.Objects.equals(
                operation.expectedLifecycleRevision(),
                plan.expectedLifecycleRevision()
        )) {
            throw new IllegalArgumentException(
                    "Population plan must match operation lifecycle revision"
            );
        }
    }
}
