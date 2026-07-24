package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Facade-only adapter used after replacement runtime construction is complete. */
final class FacadePublicPersistenceWorldReconciliationAccess
        implements HytalePublicPersistenceWorldReconciliation.Access {
    private final PersistenceDomainFacades facades;

    FacadePublicPersistenceWorldReconciliationAccess(
            PersistenceDomainFacades facades
    ) {
        this.facades = Objects.requireNonNull(facades, "facades");
    }

    @Override
    public CompletionStage<PersistenceReadResult<List<CompanionLifecycle>>>
    findAllLifecycles() {
        return facades.queries().findAllLifecycles();
    }

    @Override
    public Map<ProfileId, CompanionProfileProjectionState>
    projectedProfiles() {
        return facades.queries().projectedProfileSnapshot();
    }

    @Override
    public CompletionStage<Void> reconcileLoaded(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        PublicOperationSubmission submission =
                facades.operations().reconcileLoadedDuringStartup(
                        operationId,
                        idempotencyKey,
                        reconciliation
                );
        if (!submission.accepted()) {
            return failed(
                    "world_reconciliation_submission_"
                            + submission.admission().name().toLowerCase()
            );
        }
        return submission.completion().thenApply(result -> {
            if (result.status() != OperationWorkflowResult.Status.PUBLISHED) {
                throw new IllegalStateException(
                        "world_reconciliation_operation_"
                                + result.status().name().toLowerCase(),
                        result.failure()
                );
            }
            return null;
        });
    }

    private CompletionStage<Void> failed(String code) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(code)
        );
    }
}
