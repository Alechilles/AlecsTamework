package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;

/** Durable cleanup after a paid revival does not publish a live companion. */
final class SqlitePaidRevivalCompensationCleanup {
    private SqlitePaidRevivalCompensationCleanup() {
    }

    static SqliteManagedAdmissionParticipant managed(
            OperationEnvelope operation,
            PaidRevivalRequest request
    ) {
        return request.admissionEvidence() != null
                && request.admissionEvidence().status()
                == LifecycleAdmissionEvidence.Status.MANAGED
                ? SqliteManagedAdmissionParticipant.from(
                operation.operationId(), request.admissionEvidence()
        ) : null;
    }

    static void cleanup(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            PaidRevivalRequest request,
            SqliteManagedAdmissionParticipant managed,
            long compensatedAtMs
    ) {
        CompanionLifecycle source = request.groupAdmission().before();
        CompanionLifecycle fenced = transaction.lifecycles()
                .findByProfile(source.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "paid_revival_compensation_lifecycle_missing"
                ));
        CompanionAlias alias = transaction.identities()
                .resolveAlias(request.targetAlias()).orElse(null);
        if (!SqlitePaidRevivalSourceEvidence.fencedMatches(
                fenced, request, operation
        ) || !SqlitePaidRevivalSourceEvidence.leaseMatches(
                alias, request, operation
        )) {
            throw new IllegalStateException(
                    "paid_revival_compensation_fence_mismatch"
            );
        }
        if (managed == null) {
            new SqlitePopulationGroupTransitionParticipant(
                    request.groupAdmission()
            ).retirePrepared(transaction, operation);
        } else {
            managed.retirePrepared(transaction, operation);
        }
        if (!transaction.identities().retireAlias(
                request.targetAlias(), compensatedAtMs
        ).applied()) {
            throw new IllegalStateException(
                    "paid_revival_compensation_alias_rejected"
            );
        }
        CompanionLifecycle restored = new CompanionLifecycle(
                source.profileId(),
                source.ownerId(),
                source.state(),
                source.location(),
                fenced.revision().next(),
                null,
                compensatedAtMs,
                source.lastReconciledGeneration(),
                source.quarantineIncidentId(),
                source.ownerWorldKey()
        );
        if (!transaction.lifecycles().transition(
                new LifecycleTransition(
                        fenced.revision(),
                        operation.operationId(),
                        restored
                )
        ).applied()) {
            throw new IllegalStateException(
                    "paid_revival_compensation_lifecycle_rejected"
            );
        }
    }

    static boolean rowsRemain(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            SqliteManagedAdmissionParticipant managed
    ) {
        return managed != null
                && (!transaction.populationDomains()
                .findByOperation(operation.operationId()).isEmpty()
                || !transaction.population()
                .findByOperation(operation.operationId()).isEmpty()
                || !transaction.populationGroups()
                .findReservations(operation.operationId()).isEmpty());
    }
}
