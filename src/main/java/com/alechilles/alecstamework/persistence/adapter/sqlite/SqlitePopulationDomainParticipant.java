package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
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
    private final boolean retainCommitted;

    public SqlitePopulationDomainParticipant(
            @Nonnull List<PopulationDomainReservation> reservations
    ) {
        this(reservations, false);
    }

    /** Creates a participant that retains committed rows as the domain ledger. */
    public SqlitePopulationDomainParticipant(
            @Nonnull List<PopulationDomainReservation> reservations,
            boolean retainCommitted
    ) {
        if (reservations == null || reservations.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Domain reservations are required");
        }
        this.reservations = reservations.stream()
                .sorted(java.util.Comparator.comparing(
                        PopulationDomainReservation::bucket
                ))
                .toList();
        this.retainCommitted = retainCommitted;
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
        if (operation.phase() == OperationPhase.COMPENSATED
                || operation.phase() == OperationPhase.FAILED) {
            return actual.isEmpty();
        }
        if (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED) {
            if (!retainCommitted) {
                return actual.isEmpty();
            }
            return sameReservations(actual)
                    || matchesCanonicalSupersession(transaction, operation, actual);
        }
        if (actual.size() != reservations.size()) {
            return false;
        }
        return sameReservations(actual);
    }

    /** Retires only the exact operation-owned rows after delegated durable work. */
    @Nonnull
    public DurableOperationWork decorate(@Nonnull DurableOperationWork delegated) {
        return decorate(delegated, retainCommitted);
    }

    /** Decorates durable work and optionally retains exact committed rows. */
    @Nonnull
    public DurableOperationWork decorate(
            @Nonnull DurableOperationWork delegated,
            boolean retainCommitted
    ) {
        if (delegated == null) {
            throw new IllegalArgumentException("Domain durable work is required");
        }
        return (transaction, operation) -> {
            if (!matches(transaction, operation)) {
                throw new IllegalStateException("population_domain_reservation_missing");
            }
            List<ProjectionEventDraft> events = delegated.execute(transaction, operation);
            if (!retainCommitted && !transaction.populationDomains().retireExact(
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

    /** Settles one aggregate request to its exact live ordinal count. */
    public void settleBatch(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            int requestedUnits,
            int settledUnits
    ) {
        requireOperation(operation);
        if (!(transaction.populationDomains() instanceof SqlitePopulationDomainStore store)
                || !store.settleBatch(
                operation.operationId(), reservations, requestedUnits, settledUnits
        )) {
            throw new IllegalStateException(
                    "population_domain_batch_settlement_failed"
            );
        }
    }

    public List<PopulationDomainReservation> reservations() {
        return reservations;
    }

    private boolean sameReservations(List<PopulationDomainReservation> actual) {
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

    /**
     * Accepts only a later canonical lifecycle revision with durable evidence.
     * Existing rows, when any, must still be exact residuals of this participant's
     * original identities; unknown or foreign rows do not prove supersession.
     */
    private boolean matchesCanonicalSupersession(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            List<PopulationDomainReservation> actual
    ) {
        if (reservations.isEmpty()
                || transaction.outbox().findByOperation(operation.operationId()).isEmpty()) {
            return false;
        }
        long expectedRevision = reservations.getFirst()
                .expectedLifecycleRevision() == null
                ? Long.MIN_VALUE
                : reservations.getFirst().expectedLifecycleRevision().value();
        if (expectedRevision == Long.MIN_VALUE
                || reservations.stream().anyMatch(reservation ->
                !reservation.profileId().equals(reservations.getFirst().profileId())
                        || reservation.expectedLifecycleRevision() == null
                        || reservation.expectedLifecycleRevision().value()
                        != expectedRevision)) {
            return false;
        }
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(reservations.getFirst().profileId())
                .orElse(null);
        if (current == null
                || current.revision().value() <= expectedRevision
                || current.activeOperationId() != null
                || current.quarantined()
                || actual.size() > reservations.size()) {
            return false;
        }
        boolean[] matched = new boolean[reservations.size()];
        for (PopulationDomainReservation row : actual) {
            boolean found = false;
            for (int index = 0; index < reservations.size(); index++) {
                if (!matched[index]
                        && sameResidualIdentity(row, reservations.get(index))) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private boolean sameResidualIdentity(
            PopulationDomainReservation actual,
            PopulationDomainReservation expected
    ) {
        return actual.operationId().equals(expected.operationId())
                && actual.profileId().equals(expected.profileId())
                && java.util.Objects.equals(
                actual.expectedLifecycleRevision(),
                expected.expectedLifecycleRevision()
        )
                && actual.bucket().equals(expected.bucket())
                && actual.weight() == expected.weight()
                && actual.snapshottedMaxOwned()
                == expected.snapshottedMaxOwned()
                && actual.snapshottedMaxDeployable()
                == expected.snapshottedMaxDeployable()
                && actual.policyRevision() == expected.policyRevision()
                && actual.createdAtMs() == expected.createdAtMs();
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
