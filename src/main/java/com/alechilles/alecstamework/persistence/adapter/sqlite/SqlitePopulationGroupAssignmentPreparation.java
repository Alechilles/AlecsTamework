package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAdmission;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentPlan;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentPlanner;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentRequest;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import java.util.List;
import java.util.Objects;

/** Exact source validation and reusable shared-envelope group admission participant. */
final class SqlitePopulationGroupAssignmentPreparation
        implements PreparedOperationDetail {
    private final PopulationGroupAssignmentRequest request;

    SqlitePopulationGroupAssignmentPreparation(
            PopulationGroupAssignmentRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Population group assignment request is required"
            );
        }
        this.request = request;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        PopulationGroupAssignmentPlan plan =
                plan(transaction, operation, request);
        for (PopulationGroupReservation reservation
                : plan.reservations()) {
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
                    case ADMITTED ->
                            throw new IllegalStateException(
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
        try {
            PopulationGroupAssignmentPlan plan =
                    plan(transaction, operation, request);
            return (operation.phase() == OperationPhase.PREPARED
                    || operation.phase() == OperationPhase.RETRYABLE)
                    && transaction.populationGroups()
                    .findReservations(operation.operationId())
                    .equals(plan.reservations());
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            return false;
        }
    }

    DurableOperationWork decorate(DurableOperationWork delegated) {
        if (delegated == null) {
            throw new IllegalArgumentException(
                    "Population group durable work is required"
            );
        }
        return (transaction, operation) -> {
            int expected = plan(
                    transaction, operation, request
            ).reservations().size();
            List<com.alechilles.alecstamework.persistence.projection
                    .ProjectionEventDraft> events =
                    delegated.execute(transaction, operation);
            if (!transaction.populationGroups().retireExact(
                    operation.operationId(), expected
            )) {
                throw new IllegalStateException(
                        "population_group_reservation_retirement_failed"
                );
            }
            return events;
        };
    }

    static PopulationGroupAssignmentPlan plan(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            PopulationGroupAssignmentRequest request
    ) {
        CompanionIdentity identity = transaction.identities()
                .findProfile(request.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "population_group_profile_missing"
                ));
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(request.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "population_group_lifecycle_missing"
                ));
        PopulationGroupAssignment current =
                transaction.populationGroups()
                        .findAssignment(request.profileId())
                        .orElse(null);
        if (identity.metadataRevision()
                != request.expectedMetadataRevision()
                || !Objects.equals(
                identity.roleId(), request.expectedRoleId()
        )
                || !lifecycle.revision().equals(
                request.expectedLifecycleRevision()
        )
                || !Objects.equals(
                lifecycle.ownerId(), request.expectedOwnerId()
        )
                || !Objects.equals(
                lifecycle.ownerWorldKey(),
                request.expectedOwnerWorldKey()
        )
                || lifecycle.activeOperationId() != null
                || lifecycle.quarantined()
                || !assignmentMatches(
                current, request.expectedAssignmentRevision()
        )) {
            throw new IllegalStateException(
                    "population_group_assignment_source_mismatch"
            );
        }
        return PopulationGroupAssignmentPlanner.plan(
                operation.operationId(),
                request,
                current,
                lifecycle
        );
    }

    private static boolean assignmentMatches(
            PopulationGroupAssignment current,
            Long expectedRevision
    ) {
        return current == null
                ? expectedRevision == null
                : expectedRevision != null
                && current.assignmentRevision() == expectedRevision;
    }
}

