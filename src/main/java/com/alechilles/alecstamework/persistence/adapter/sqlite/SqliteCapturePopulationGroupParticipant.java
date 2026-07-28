package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CapturePopulationGroupEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkEvidence;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAdmission;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import java.util.List;
import javax.annotation.Nonnull;

/** Capture-aware role-changing population-group reservation participant. */
final class SqliteCapturePopulationGroupParticipant
        implements PreparedOperationDetail {
    private final CaptureTameAndLinkEvidence evidence;

    SqliteCapturePopulationGroupParticipant(
            @Nonnull CaptureTameAndLinkEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Capture group participant evidence is required"
            );
        }
        this.evidence = evidence;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        requireOperation(operation);
        requireSource(transaction, operation, false);
        for (PopulationGroupReservation reservation
                : reservations()) {
            PopulationGroupAdmission admission =
                    transaction.populationGroups().reserve(reservation);
            if (!admission.admitted()) {
                throw new IllegalStateException(
                        "capture_population_group_"
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
        if (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED) {
            return exactTarget(transaction)
                    && transaction.populationGroups()
                    .findReservations(operation.operationId()).isEmpty();
        }
        if (operation.phase() == OperationPhase.COMPENSATED) {
            return exactCompensatedSource(transaction)
                    && transaction.populationGroups()
                    .findReservations(operation.operationId()).isEmpty();
        }
        if (operation.phase() == OperationPhase.FAILED) {
            return exactSource(transaction)
                    && transaction.populationGroups()
                    .findReservations(operation.operationId()).isEmpty();
        }
        return requireSource(transaction, operation, true)
                && transaction.populationGroups()
                .findReservations(operation.operationId())
                .equals(reservations());
    }

    @Nonnull
    DurableOperationWork decorate(
            @Nonnull DurableOperationWork durableWork
    ) {
        if (durableWork == null) {
            throw new IllegalArgumentException(
                    "Capture durable work is required"
            );
        }
        return (transaction, operation) -> {
            if (!matches(transaction, operation)) {
                throw new IllegalStateException(
                        "capture_population_group_reservation_missing"
                );
            }
            var events = durableWork.execute(transaction, operation);
            if (!transaction.populationGroups().retireExact(
                    operation.operationId(), reservations().size()
            )) {
                throw new IllegalStateException(
                        "capture_population_group_retirement_failed"
                );
            }
            return events;
        };
    }

    private boolean requireSource(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            boolean allowFence
    ) {
        if (!exactSource(transaction)) {
            if (allowFence && exactFencedLifecycle(transaction, operation)) {
                return true;
            }
            if (allowFence) {
                return false;
            }
            throw new IllegalStateException(
                    "capture_population_group_source_mismatch"
            );
        }
        return true;
    }

    private boolean exactSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionIdentity identity = transaction.identities()
                .findProfile(evidence.expectedIdentity().profileId())
                .orElse(null);
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(evidence.expectedIdentity().profileId())
                .orElse(null);
        PopulationGroupAssignment assignment =
                transaction.populationGroups().findAssignment(
                        evidence.expectedIdentity().profileId()
                ).orElse(null);
        return evidence.expectedIdentity().equals(identity)
                && evidence.expectedLifecycle().equals(lifecycle)
                && java.util.Objects.equals(
                groups().expectedAssignment(), assignment
        );
    }

    private boolean exactFencedLifecycle(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionIdentity identity = transaction.identities()
                .findProfile(evidence.expectedIdentity().profileId())
                .orElse(null);
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(evidence.expectedIdentity().profileId())
                .orElse(null);
        PopulationGroupAssignment assignment =
                transaction.populationGroups().findAssignment(
                        evidence.expectedIdentity().profileId()
                ).orElse(null);
        return evidence.expectedIdentity().equals(identity)
                && java.util.Objects.equals(
                groups().expectedAssignment(), assignment
        )
                && SqliteCompanionCapturePreparation.matchesFence(
                lifecycle,
                operation,
                evidence.expectedLifecycle(),
                evidence.finalLifecycle().stateChangedAtMs()
        );
    }

    private boolean exactCompensatedSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionLifecycle source = evidence.expectedLifecycle();
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(source.profileId()).orElse(null);
        return transaction.identities()
                .findProfile(source.profileId())
                .filter(evidence.expectedIdentity()::equals)
                .isPresent()
                && lifecycle != null
                && lifecycle.profileId().equals(source.profileId())
                && java.util.Objects.equals(
                lifecycle.ownerId(), source.ownerId()
        )
                && lifecycle.state() == source.state()
                && lifecycle.location().equals(source.location())
                && lifecycle.revision().equals(
                source.revision().next().next()
        )
                && lifecycle.activeOperationId() == null
                && lifecycle.lastReconciledGeneration().equals(
                source.lastReconciledGeneration()
        )
                && java.util.Objects.equals(
                lifecycle.quarantineIncidentId(),
                source.quarantineIncidentId()
        )
                && java.util.Objects.equals(
                lifecycle.ownerWorldKey(), source.ownerWorldKey()
        )
                && java.util.Objects.equals(
                groups().expectedAssignment(),
                transaction.populationGroups()
                        .findAssignment(source.profileId()).orElse(null)
        );
    }

    private boolean exactTarget(
            SqlitePersistenceTransactionContext transaction
    ) {
        return transaction.identities()
                .findProfile(evidence.targetIdentity().profileId())
                .filter(evidence.targetIdentity()::equals)
                .isPresent()
                && transaction.lifecycles()
                .findByProfile(evidence.finalLifecycle().profileId())
                .filter(evidence.finalLifecycle()::equals)
                .isPresent()
                && transaction.populationGroups().findAssignment(
                evidence.targetIdentity().profileId()
        ).filter(groups().targetPlan().target()::equals).isPresent();
    }

    private void requireOperation(OperationEnvelope operation) {
        boolean reservationOperationMatches =
                reservations().isEmpty()
                        || reservations().stream().allMatch(reservation ->
                        operation != null
                                && operation.operationId().equals(
                                reservation.operationId()
                        ));
        if (operation == null
                || !reservationOperationMatches
                || !java.util.Objects.equals(
                operation.expectedLifecycleRevision(),
                evidence.expectedLifecycle().revision()
        )) {
            throw new IllegalArgumentException(
                    "Capture group plan must match operation evidence"
            );
        }
    }

    private CapturePopulationGroupEvidence groups() {
        return evidence.populationGroups();
    }

    private List<PopulationGroupReservation> reservations() {
        return groups().targetPlan().reservations();
    }
}
