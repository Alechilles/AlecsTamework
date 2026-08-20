package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.ArrayList;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Atomic owner, family-group, named-domain, and source-convergence participant. */
public final class SqliteManagedAdmissionParticipant
        implements PreparedOperationDetail {
    private final SqlitePopulationDomainParticipant domain;
    @Nullable
    private final SqliteOwnerPopulationParticipant owner;
    @Nullable
    private final SqlitePopulationGroupTransitionParticipant groups;
    @Nullable
    private final SqlitePopulationDomainConvergenceParticipant convergence;
    private final PreparedOperationDetail composed;

    public SqliteManagedAdmissionParticipant(
            @Nonnull SqlitePopulationDomainParticipant domain,
            @Nullable SqliteOwnerPopulationParticipant owner,
            @Nullable SqlitePopulationGroupTransitionParticipant groups
    ) {
        this(domain, owner, groups, null);
    }

    SqliteManagedAdmissionParticipant(
            @Nonnull SqlitePopulationDomainParticipant domain,
            @Nullable SqliteOwnerPopulationParticipant owner,
            @Nullable SqlitePopulationGroupTransitionParticipant groups,
            @Nullable SqlitePopulationDomainConvergenceParticipant convergence
    ) {
        this.domain = require(domain);
        this.owner = owner;
        this.groups = groups;
        this.convergence = convergence;
        this.composed = compose(domain, owner, groups, convergence);
    }

    /** Rebuilds the exact participant from persisted admission evidence. */
    @Nonnull
    public static SqliteManagedAdmissionParticipant from(
            @Nonnull OperationId operationId,
            @Nonnull LifecycleAdmissionEvidence evidence
    ) {
        if (operationId == null || evidence == null
                || evidence.status() != LifecycleAdmissionEvidence.Status.MANAGED
                || evidence.payload() == null) {
            throw new IllegalArgumentException(
                    "Managed lifecycle admission evidence is required"
            );
        }
        PopulationDomainAdmissionOperation.Payload payload = evidence.payload();
        PopulationAdmissionComposition composition = evidence.composition();
        PopulationDomainConvergencePlan plan = evidence.convergencePlan();
        if (plan != null) {
            requirePlanMatchesPayload(operationId, payload, plan);
        }
        return new SqliteManagedAdmissionParticipant(
                new SqlitePopulationDomainParticipant(
                        payload.reservations(operationId), true
                ),
                owner(composition, payload),
                groups(composition, payload),
                plan == null
                        ? null
                        : new SqlitePopulationDomainConvergenceParticipant(plan)
        );
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) throws Exception {
        composed.prepare(transaction, operation);
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) throws Exception {
        return composed.matches(transaction, operation);
    }

    /** Decorates durable work with owner, group, domain, and convergence settlement. */
    @Nonnull
    public DurableOperationWork decorate(@Nonnull DurableOperationWork delegated) {
        if (delegated == null) {
            throw new IllegalArgumentException(
                    "Managed admission durable work is required"
            );
        }
        DurableOperationWork decorated = delegated;
        if (groups != null) {
            decorated = groups.decorate(decorated);
        }
        if (owner != null) {
            decorated = owner.decorate(decorated);
        }
        decorated = domain.decorate(decorated, true);
        return convergence == null
                ? decorated
                : convergence.decorate(decorated);
    }

    /** Retires every exact reservation authority owned by this operation. */
    public void retirePrepared(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (owner == null) {
            retireOwnerRows(transaction, operation);
        } else {
            owner.retirePrepared(transaction, operation);
        }
        if (groups == null) {
            retireGroupRows(transaction, operation);
        } else {
            groups.retirePrepared(transaction, operation);
        }
        domain.retirePrepared(transaction, operation);
    }

    /** Settles domain units and retires owner/group reservations atomically. */
    public void settleBatch(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            int requestedUnits,
            int settledUnits
    ) {
        domain.settleBatch(transaction, operation, requestedUnits, settledUnits);
        retireCompanionRows(transaction, operation);
    }

    /** Returns the domain participant used for the durable weighted ledger. */
    @Nonnull
    public SqlitePopulationDomainParticipant domain() {
        return domain;
    }

    private static PreparedOperationDetail compose(
            SqlitePopulationDomainParticipant domain,
            SqliteOwnerPopulationParticipant owner,
            SqlitePopulationGroupTransitionParticipant groups,
            SqlitePopulationDomainConvergenceParticipant convergence
    ) {
        ArrayList<PreparedOperationDetail> details = new ArrayList<>();
        if (owner != null) {
            details.add(owner);
        }
        if (groups != null) {
            details.add(groups);
        }
        details.add(domain);
        if (convergence != null) {
            details.add(convergence);
        }
        return PreparedOperationDetail.compose(
                details.toArray(PreparedOperationDetail[]::new)
        );
    }

    @Nullable
    private static SqliteOwnerPopulationParticipant owner(
            PopulationAdmissionComposition composition,
            PopulationDomainAdmissionOperation.Payload payload
    ) {
        return composition == null || composition.ownerPlan() == null
                ? null
                : new SqliteOwnerPopulationParticipant(
                composition.ownerPlan(), payload.requestedCount()
        );
    }

    @Nullable
    private static SqlitePopulationGroupTransitionParticipant groups(
            PopulationAdmissionComposition composition,
            PopulationDomainAdmissionOperation.Payload payload
    ) {
        return composition == null || composition.groupRequest() == null
                ? null
                : new SqlitePopulationGroupTransitionParticipant(
                composition.groupRequest(), payload.requestedCount()
        );
    }

    private static void requirePlanMatchesPayload(
            OperationId operationId,
            PopulationDomainAdmissionOperation.Payload payload,
            PopulationDomainConvergencePlan plan
    ) {
        if (!plan.profileId().equals(payload.profileId())
                || !plan.sourceLifecycleRevision().equals(
                payload.expectedLifecycleRevision()
        )
                || plan.sourceState() != payload.sourceLifecycle()
                || !java.util.Objects.equals(
                plan.sourceOwner(), payload.sourceOwnerId()
        )
                || !java.util.Objects.equals(
                plan.sourceWorldKey(), payload.sourceWorldKey()
        )
                || plan.targetState() != payload.targetLifecycle()
                || !java.util.Objects.equals(plan.targetOwner(), payload.ownerId())
                || !java.util.Objects.equals(
                plan.targetWorldKey(), payload.ownerWorldKey()
        )
                || !plan.targetReservations().equals(
                payload.reservations(operationId)
        )) {
            throw new IllegalArgumentException(
                    "Lifecycle convergence evidence does not match durable payload"
            );
        }
    }

    private void retireCompanionRows(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (owner == null) {
            retireOwnerRows(transaction, operation);
        } else {
            owner.retirePrepared(transaction, operation);
        }
        if (groups == null) {
            retireGroupRows(transaction, operation);
        } else {
            groups.retirePrepared(transaction, operation);
        }
    }

    private void retireOwnerRows(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        int expected = transaction.population()
                .findByOperation(operation.operationId()).size();
        if (!transaction.population().retireExact(
                operation.operationId(), expected
        )) {
            throw new IllegalStateException(
                    "owner_population_reservation_retirement_failed"
            );
        }
    }

    private void retireGroupRows(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        int expected = transaction.populationGroups()
                .findReservations(operation.operationId()).size();
        if (!transaction.populationGroups().retireExact(
                operation.operationId(), expected
        )) {
            throw new IllegalStateException(
                    "population_group_reservation_retirement_failed"
            );
        }
    }

    private static SqlitePopulationDomainParticipant require(
            SqlitePopulationDomainParticipant domain
    ) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain participant is required");
        }
        return domain;
    }
}
