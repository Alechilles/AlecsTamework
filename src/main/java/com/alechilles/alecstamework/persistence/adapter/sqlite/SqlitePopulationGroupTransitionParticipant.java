package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAdmission;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;

/** Reusable shared-envelope participant for positive group lifecycle admission. */
final class SqlitePopulationGroupTransitionParticipant
        implements PreparedOperationDetail {
    private final PopulationGroupTransitionAdmissionRequest request;

    SqlitePopulationGroupTransitionParticipant(
            PopulationGroupTransitionAdmissionRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Group transition admission request is required"
            );
        }
        this.request = request;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        for (PopulationGroupReservation reservation
                : plan(transaction, operation)) {
            PopulationGroupAdmission admission =
                    transaction.populationGroups().reserve(reservation);
            if (!admission.admitted()) {
                throw new IllegalStateException(switch (
                        admission.status()
                ) {
                    case OWNED_CAPACITY_REACHED ->
                            "population_group_owned_capacity_reached";
                    case ACTIVE_CAPACITY_REACHED ->
                            "population_group_active_capacity_reached";
                    case CONFLICT ->
                            "population_group_reservation_conflict";
                    case ADMITTED -> throw new IllegalStateException(
                            "population_group_admission_invalid"
                    );
                });
            }
        }
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED) {
            return true;
        }
        if (operation.phase() == OperationPhase.COMPENSATED) {
            return transaction.populationGroups()
                    .findReservations(operation.operationId()).isEmpty();
        }
        try {
            return (operation.phase() == OperationPhase.PREPARED
                    || operation.phase() == OperationPhase.RETRYABLE
                    || operation.phase() == OperationPhase.LIVE_APPLYING
                    || operation.phase() == OperationPhase.COMPENSATING)
                    && transaction.populationGroups()
                    .findReservations(operation.operationId())
                    .equals(plan(transaction, operation));
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return false;
        }
    }

    DurableOperationWork decorate(DurableOperationWork delegated) {
        if (delegated == null) {
            throw new IllegalArgumentException(
                    "Group transition durable work is required"
            );
        }
        return (transaction, operation) -> {
            int expected = plannedReservationCount(
                    transaction, operation
            );
            List<ProjectionEventDraft> events =
                    delegated.execute(transaction, operation);
            retireExact(transaction, operation, expected);
            return events;
        };
    }

    /** Retires only this participant's exact prepared reservation set. */
    void retirePrepared(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        retireExact(
                transaction,
                operation,
                plannedReservationCount(transaction, operation)
        );
    }

    private int plannedReservationCount(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        return plan(transaction, operation).size();
    }

    private void retireExact(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            int expected
    ) {
        if (!transaction.populationGroups().retireExact(
                operation.operationId(), expected
        )) {
            throw new IllegalStateException(
                    "population_group_reservation_retirement_failed"
            );
        }
    }

    private List<PopulationGroupReservation> plan(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(request.before().profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "population_group_transition_lifecycle_missing"
                ));
        PopulationGroupAssignment assignment =
                transaction.populationGroups()
                        .findAssignment(request.before().profileId())
                        .orElseThrow(() -> new IllegalStateException(
                                "population_group_transition_assignment_missing"
                        ));
        if (!sourceMatches(lifecycle, operation)) {
            throw new IllegalStateException(
                    "population_group_transition_source_mismatch"
            );
        }
        return PopulationGroupTransitionAdmissionPlanner.plan(
                operation.operationId(), request, assignment
        );
    }

    private boolean sourceMatches(
            CompanionLifecycle lifecycle,
            OperationEnvelope operation
    ) {
        CompanionLifecycle before = request.before();
        if (lifecycle.equals(before)
                && lifecycle.activeOperationId() == null
                && !lifecycle.quarantined()) {
            return true;
        }
        return lifecycle.profileId().equals(before.profileId())
                && java.util.Objects.equals(
                lifecycle.ownerId(), before.ownerId()
        )
                && lifecycle.state() == before.state()
                && lifecycle.location().equals(before.location())
                && lifecycle.revision().equals(before.revision().next())
                && operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                && lifecycle.stateChangedAtMs() == request.requestedAtMs()
                && lifecycle.lastReconciledGeneration().equals(
                before.lastReconciledGeneration()
        )
                && java.util.Objects.equals(
                lifecycle.quarantineIncidentId(),
                before.quarantineIncidentId()
        )
                && java.util.Objects.equals(
                lifecycle.ownerWorldKey(), before.ownerWorldKey()
        )
                && !lifecycle.quarantined();
    }
}

