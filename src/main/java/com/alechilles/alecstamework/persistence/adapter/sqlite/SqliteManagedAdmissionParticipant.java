package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Atomic owner, family-group, and named-domain preparation participant. */
public final class SqliteManagedAdmissionParticipant
        implements PreparedOperationDetail {
    private final SqlitePopulationDomainParticipant domain;
    @Nullable
    private final SqliteOwnerPopulationParticipant owner;
    @Nullable
    private final SqlitePopulationGroupTransitionParticipant groups;
    private final PreparedOperationDetail composed;

    public SqliteManagedAdmissionParticipant(
            @Nonnull SqlitePopulationDomainParticipant domain,
            @Nullable SqliteOwnerPopulationParticipant owner,
            @Nullable SqlitePopulationGroupTransitionParticipant groups
    ) {
        this.domain = require(domain);
        this.owner = owner;
        this.groups = groups;
        ArrayList<PreparedOperationDetail> details = new ArrayList<>();
        if (owner != null) {
            details.add(owner);
        }
        if (groups != null) {
            details.add(groups);
        }
        details.add(domain);
        this.composed = PreparedOperationDetail.compose(
                details.toArray(PreparedOperationDetail[]::new)
        );
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
        return new SqliteManagedAdmissionParticipant(
                new SqlitePopulationDomainParticipant(
                        payload.reservations(operationId),
                        true
                ),
                composition == null || composition.ownerPlan() == null
                        ? null
                        : new SqliteOwnerPopulationParticipant(
                        composition.ownerPlan(), payload.requestedCount()
                ),
                composition == null || composition.groupRequest() == null
                        ? null
                        : new SqlitePopulationGroupTransitionParticipant(
                        composition.groupRequest(), payload.requestedCount()
                )
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

    /** Decorates durable work with owner, group, and retained domain settlement. */
    @Nonnull
    public DurableOperationWork decorate(@Nonnull DurableOperationWork delegated) {
        if (delegated == null) {
            throw new IllegalArgumentException("Managed admission durable work is required");
        }
        DurableOperationWork decorated = delegated;
        if (groups != null) {
            decorated = groups.decorate(decorated);
        }
        if (owner != null) {
            decorated = owner.decorate(decorated);
        }
        return domain.decorate(decorated, true);
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
            throw new IllegalStateException("owner_population_reservation_retirement_failed");
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
            throw new IllegalStateException("population_group_reservation_retirement_failed");
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
