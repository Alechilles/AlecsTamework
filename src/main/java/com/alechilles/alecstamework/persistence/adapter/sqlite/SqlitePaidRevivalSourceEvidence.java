package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;

/** Exact shared lifecycle and alias fence evidence for paid revival. */
final class SqlitePaidRevivalSourceEvidence {
    private SqlitePaidRevivalSourceEvidence() {
    }

    static CompanionLifecycle fenced(
            PaidRevivalRequest request,
            OperationEnvelope operation
    ) {
        CompanionLifecycle source = request.groupAdmission().before();
        return new CompanionLifecycle(
                source.profileId(),
                source.ownerId(),
                source.state(),
                source.location(),
                source.revision().next(),
                operation.operationId(),
                request.requestedAtMs(),
                source.lastReconciledGeneration(),
                source.quarantineIncidentId(),
                source.ownerWorldKey()
        );
    }

    static boolean fencedMatches(
            CompanionLifecycle actual,
            PaidRevivalRequest request,
            OperationEnvelope operation
    ) {
        return actual != null
                && actual.equals(fenced(request, operation))
                && !actual.quarantined();
    }

    static boolean leaseMatches(
            CompanionAlias actual,
            PaidRevivalRequest request,
            OperationEnvelope operation
    ) {
        return actual != null
                && actual.alias().equals(request.targetAlias())
                && actual.profileId().equals(
                        request.sourceSnapshot().profileId()
                )
                && actual.state() == CompanionAlias.State.LEASED
                && operation.operationId().equals(
                        actual.leaseOperationId()
                )
                && actual.mappedAtMs() == request.requestedAtMs()
                && actual.retiredAtMs() == null;
    }
}
