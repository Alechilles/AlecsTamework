package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlanner;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.Objects;

/** Normalizes managed admission evidence against the canonical lifecycle read. */
final class SqliteManagedLifecycleAdmissionEvidence {
    private SqliteManagedLifecycleAdmissionEvidence() {
    }

    static LifecycleAdmissionEvidence normalizeActiveTransition(
            LifecycleAdmissionEvidence evidence,
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source,
            CompanionLifecycle target,
            OperationId operationId,
            String canonicalEvidenceFailure,
            String convergenceFailure
    ) {
        if (evidence == null) {
            throw new IllegalStateException(
                    "Lifecycle admission returned no evidence"
            );
        }
        if (evidence.status() != LifecycleAdmissionEvidence.Status.MANAGED) {
            return evidence;
        }
        var payload = evidence.payload();
        CompanionLifecycle lifecycle = source.lifecycle();
        if (payload == null
                || !payload.profileId().equals(lifecycle.profileId())
                || !Objects.equals(
                payload.expectedLifecycleRevision(), lifecycle.revision()
        )
                || payload.sourceLifecycle() != lifecycle.state()
                || !Objects.equals(payload.sourceOwnerId(), lifecycle.ownerId())
                || !Objects.equals(
                payload.sourceWorldKey(), lifecycle.ownerWorldKey()
        )
                || payload.targetLifecycle() != LifecycleState.ACTIVE
                || !Objects.equals(payload.ownerId(), target.ownerId())
                || !Objects.equals(
                payload.ownerWorldKey(), target.ownerWorldKey()
        )) {
            throw new IllegalStateException(canonicalEvidenceFailure);
        }
        PopulationDomainConvergencePlan plan =
                PopulationDomainConvergencePlanner.plan(
                        lifecycle.profileId(),
                        lifecycle.revision(),
                        lifecycle.ownerId(),
                        lifecycle.ownerWorldKey(),
                        lifecycle.state(),
                        target.ownerId(),
                        target.ownerWorldKey(),
                        LifecycleState.ACTIVE,
                        source.committedDomainRows(),
                        payload.reservations(operationId)
                );
        if (evidence.convergencePlan() != null
                && !evidence.convergencePlan().equals(plan)) {
            throw new IllegalStateException(convergenceFailure);
        }
        return LifecycleAdmissionEvidence.managed(
                payload, evidence.composition(), plan
        );
    }
}
