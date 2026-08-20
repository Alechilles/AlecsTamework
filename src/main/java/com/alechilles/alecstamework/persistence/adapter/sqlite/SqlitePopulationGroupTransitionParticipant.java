package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAdmission;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupLifecycleClassifier;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;

/** Reusable shared-envelope participant for positive group lifecycle admission. */
public final class SqlitePopulationGroupTransitionParticipant
        implements PreparedOperationDetail {
    private final PopulationGroupTransitionAdmissionRequest request;
    private final int units;

    public SqlitePopulationGroupTransitionParticipant(
            PopulationGroupTransitionAdmissionRequest request
    ) {
        this(request, 1);
    }

    /** Creates one group participant for an aggregate admission. */
    public SqlitePopulationGroupTransitionParticipant(
            PopulationGroupTransitionAdmissionRequest request,
            int units
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Group transition admission request is required"
            );
        }
        if (units <= 0) {
            throw new IllegalArgumentException("Admission units must be positive");
        }
        this.request = request;
        this.units = units;
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
    public void retirePrepared(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        int expected = transaction.populationGroups()
                .findReservations(operation.operationId()).size();
        retireExact(transaction, operation, expected);
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
        if (isNewProfileEvidence(operation)) {
            return newProfilePlan(operation);
        }
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
        ).stream().map(reservation -> new PopulationGroupReservation(
                reservation.operationId(),
                reservation.profileId(),
                reservation.expectedLifecycleRevision(),
                reservation.bucket(),
                Math.multiplyExact(reservation.ownedDelta(), units),
                Math.multiplyExact(reservation.activeDelta(), units),
                reservation.snapshottedMaxOwned(),
                reservation.snapshottedMaxActive(),
                reservation.policyRevision(),
                reservation.createdAtMs()
        )).toList();
    }

    private List<PopulationGroupReservation> newProfilePlan(
            OperationEnvelope operation
    ) {
        CompanionLifecycle after = request.after();
        boolean owned = PopulationGroupLifecycleClassifier.consumesOwned(
                after.state()
        );
        boolean active = PopulationGroupLifecycleClassifier.consumesActive(
                after.state()
        );
        return request.policies().stream()
                .map(policy -> newProfileReservation(
                        operation, after, policy, owned, active
                ))
                .filter(reservation -> reservation.ownedDelta() > 0
                        || reservation.activeDelta() > 0)
                .toList();
    }

    private PopulationGroupReservation newProfileReservation(
            OperationEnvelope operation,
            CompanionLifecycle after,
            PopulationGroupPolicy policy,
            boolean owned,
            boolean active
    ) {
        String world = policy.scope() == PopulationGroupScope.PER_WORLD
                ? after.ownerWorldKey()
                : null;
        if (policy.scope() == PopulationGroupScope.PER_WORLD && world == null) {
            throw new IllegalStateException(
                    "population_group_new_profile_owner_world_missing"
            );
        }
        return new PopulationGroupReservation(
                operation.operationId(),
                after.profileId(),
                null,
                new PopulationGroupBucket(
                        after.ownerId(), policy.groupId(), policy.scope(), world
                ),
                owned ? units : 0,
                active ? units : 0,
                policy.maxOwnedPerOwner(),
                policy.maxActivePerOwner(),
                policy.policyRevision(),
                request.requestedAtMs()
        );
    }

    private boolean isNewProfileEvidence(OperationEnvelope operation) {
        CompanionLifecycle before = request.before();
        CompanionLifecycle after = request.after();
        return operation.expectedLifecycleRevision() == null
                && before.ownerId() == null
                && before.state() == LifecycleState.RELEASED
                && before.location().kind() == LifecycleLocationKind.NONE
                && before.revision().equals(LifecycleRevision.INITIAL)
                && operation.operationId().equals(before.activeOperationId())
                && after.ownerId() != null
                && operation.operationId().equals(after.activeOperationId())
                && after.revision().equals(before.revision().next());
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

