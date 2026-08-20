package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmission;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainReservation;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import javax.annotation.Nonnull;

/** Shared-operation participant for exact weighted named-domain reservations. */
public final class SqlitePopulationDomainParticipant
        implements PreparedOperationDetail {
    private final List<PopulationDomainReservation> reservations;

    public SqlitePopulationDomainParticipant(
            @Nonnull List<PopulationDomainReservation> reservations
    ) {
        if (reservations == null || reservations.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Domain reservations are required");
        }
        this.reservations = reservations.stream()
                .sorted(java.util.Comparator.comparing(
                        PopulationDomainReservation::bucket
                ))
                .toList();
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        requireOperation(operation);
        for (PopulationDomainReservation reservation : reservations) {
            PopulationDomainAdmission admission =
                    transaction.populationDomains().reserve(reservation);
            if (!admission.admitted()) {
                throw new IllegalStateException(
                        "population_domain_"
                                + admission.status().name().toLowerCase()
                );
            }
        }
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        requireOperation(operation);
        List<PopulationDomainReservation> actual = transaction
                .populationDomains()
                .findByOperation(operation.operationId());
        if (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED
                || operation.phase() == OperationPhase.COMPENSATED
                || operation.phase() == OperationPhase.FAILED) {
            return actual.isEmpty();
        }
        if (actual.size() != reservations.size()) {
            return false;
        }
        for (int index = 0; index < reservations.size(); index++) {
            if (!sameStored(actual.get(index), reservations.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** Retires only the exact operation-owned rows after delegated durable work. */
    @Nonnull
    public DurableOperationWork decorate(@Nonnull DurableOperationWork delegated) {
        if (delegated == null) {
            throw new IllegalArgumentException("Domain durable work is required");
        }
        return (transaction, operation) -> {
            if (!matches(transaction, operation)) {
                throw new IllegalStateException("population_domain_reservation_missing");
            }
            List<ProjectionEventDraft> events = delegated.execute(transaction, operation);
            if (!transaction.populationDomains().retireExact(
                    operation.operationId(), reservations.size()
            )) {
                throw new IllegalStateException(
                        "population_domain_reservation_retirement_failed"
                );
            }
            return events;
        };
    }

    public void retirePrepared(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        requireOperation(operation);
        if (!transaction.populationDomains().retireExact(
                operation.operationId(), reservations.size()
        )) {
            throw new IllegalStateException(
                    "population_domain_reservation_retirement_failed"
            );
        }
    }

    public List<PopulationDomainReservation> reservations() {
        return reservations;
    }

    private void requireOperation(OperationEnvelope operation) {
        if (operation == null || reservations.stream().anyMatch(
                reservation -> !reservation.operationId().equals(operation.operationId())
        )) {
            throw new IllegalArgumentException("Domain reservations must match operation identity");
        }
    }

    /** The v2 reservation table omits provider and managed-config revisions. */
    private boolean sameStored(
            PopulationDomainReservation stored,
            PopulationDomainReservation expected
    ) {
        return stored.operationId().equals(expected.operationId())
                && stored.profileId().equals(expected.profileId())
                && java.util.Objects.equals(
                        stored.expectedLifecycleRevision(),
                        expected.expectedLifecycleRevision()
                )
                && stored.bucket().equals(expected.bucket())
                && stored.ownedDelta() == expected.ownedDelta()
                && stored.deployableDelta() == expected.deployableDelta()
                && stored.weight() == expected.weight()
                && stored.snapshottedMaxOwned()
                        == expected.snapshottedMaxOwned()
                && stored.snapshottedMaxDeployable()
                        == expected.snapshottedMaxDeployable()
                && stored.policyRevision() == expected.policyRevision()
                && stored.createdAtMs() == expected.createdAtMs();
    }
}
