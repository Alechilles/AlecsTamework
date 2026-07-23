package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.population.group.PopulationGroupAdmission;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupProvisioningAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningRequest;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import java.util.List;

/** Shared-envelope owned-group admission for a not-yet-created profile. */
final class SqlitePopulationGroupProvisioningParticipant
        implements PreparedOperationDetail {
    private final CompanionProvisioningRequest request;

    SqlitePopulationGroupProvisioningParticipant(
            CompanionProvisioningRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Provisioning group admission request is required"
            );
        }
        this.request = request;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        for (PopulationGroupReservation reservation : plan(operation)) {
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
        List<PopulationGroupReservation> actual =
                transaction.populationGroups()
                        .findReservations(operation.operationId());
        if (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED) {
            return actual.isEmpty();
        }
        return (operation.phase() == OperationPhase.PREPARED
                || operation.phase() == OperationPhase.RETRYABLE)
                && actual.equals(plan(operation));
    }

    DurableOperationWork decorate(DurableOperationWork delegated) {
        if (delegated == null) {
            throw new IllegalArgumentException(
                    "Provisioning group durable work is required"
            );
        }
        return (transaction, operation) -> {
            List<com.alechilles.alecstamework.persistence.projection
                    .ProjectionEventDraft> events =
                    delegated.execute(transaction, operation);
            if (!transaction.populationGroups().retireExact(
                    operation.operationId(), plan(operation).size()
            )) {
                throw new IllegalStateException(
                        "population_group_reservation_retirement_failed"
                );
            }
            return events;
        };
    }

    private List<PopulationGroupReservation> plan(
            OperationEnvelope operation
    ) {
        if (operation == null
                || operation.expectedLifecycleRevision() != null) {
            throw new IllegalArgumentException(
                    "Provisioning operation must have no source revision"
            );
        }
        return PopulationGroupProvisioningAdmissionPlanner.plan(
                operation.operationId(),
                request.lifecycle(),
                request.groupAssignment(),
                request.groupPolicies()
        );
    }
}
