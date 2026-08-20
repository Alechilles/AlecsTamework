package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEngine;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Contains uncertain live admission effects until the owning reconciler reads them back. */
final class PopulationDomainAdmissionRecovery {
    private PopulationDomainAdmissionRecovery() {
    }

    static CompletionStage<OperationWorkflowResult> contain(
            SqliteOperationEngine engine,
            OperationEnvelope operation,
            LongSupplier clock
    ) {
        CompletionStage<PersistenceTransactionResult<OperationEnvelope>> transitioned;
        if (operation.phase() == OperationPhase.UNKNOWN
                || operation.phase() == OperationPhase.RETRYABLE
                || operation.phase() == OperationPhase.COMPENSATING) {
            transitioned = CompletableFuture.completedFuture(
                    new PersistenceTransactionResult.Committed<>(operation)
            );
        } else if (operation.phase() != OperationPhase.LIVE_APPLYING) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "domain_admission_claim_not_live"
            ));
        } else {
            transitioned = engine.transition(
                    operation,
                    OperationPhase.UNKNOWN,
                    "LIVE_OUTCOME_UNKNOWN",
                    "domain_admission_live_effect_unreadable",
                    clock.getAsLong()
            ).completion();
        }
        return transitioned.thenCompose(result -> {
            OperationEnvelope current = result instanceof PersistenceTransactionResult.Committed<OperationEnvelope> committed
                    ? committed.value() : operation;
            return engine.containUnknown(
                    current,
                    "domain_admission_live_effect_unknown",
                    "Managed admission live effect requires positive child readback",
                    current.participants(),
                    clock.getAsLong()
            ).completion().thenApply(containment -> new OperationWorkflowResult(
                    OperationWorkflowResult.Status.LIVE_UNKNOWN,
                    current,
                    List.of(),
                    new IllegalStateException("domain_admission_live_effect_unknown")
            ));
        });
    }
}
