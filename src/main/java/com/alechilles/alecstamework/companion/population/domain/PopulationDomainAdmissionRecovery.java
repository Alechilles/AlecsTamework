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
        return transitioned.handle((result, failure) -> afterTransition(
                engine, operation, clock, result, failure
        )).thenCompose(stage -> stage);
    }

    private static CompletionStage<OperationWorkflowResult> afterTransition(
            SqliteOperationEngine engine,
            OperationEnvelope original,
            LongSupplier clock,
            PersistenceTransactionResult<OperationEnvelope> result,
            Throwable failure
    ) {
        if (failure != null) {
            return CompletableFuture.completedFuture(retryable(
                    original, "domain_admission_transition_unverified", failure
            ));
        }
        if (!(result instanceof PersistenceTransactionResult.Committed<?> committed)
                || !(committed.value() instanceof OperationEnvelope current)) {
            return CompletableFuture.completedFuture(retryable(
                    original,
                    "domain_admission_transition_unverified",
                    new IllegalStateException("domain_admission_transition_readback_failed")
            ));
        }
        return containAndVerify(engine, current, clock);
    }

    private static CompletionStage<OperationWorkflowResult> containAndVerify(
            SqliteOperationEngine engine,
            OperationEnvelope operation,
            LongSupplier clock
    ) {
        try {
            return engine.containUnknown(
                    operation,
                    "domain_admission_live_effect_unknown",
                    "Managed admission live effect requires positive child readback",
                    operation.participants(),
                    clock.getAsLong()
            ).completion().handle((containment, failure) -> containmentResult(
                    operation, containment, failure
            ));
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(retryable(
                    operation, "domain_admission_containment_unverified", failure
            ));
        }
    }

    private static OperationWorkflowResult containmentResult(
            OperationEnvelope operation,
            PersistenceTransactionResult<?> containment,
            Throwable failure
    ) {
        if (failure != null) {
            return retryable(
                    operation, "domain_admission_containment_unverified", failure
            );
        }
        if (!(containment instanceof PersistenceTransactionResult.Committed<?> committed)
                || committed.value() == null) {
            return retryable(
                    operation,
                    "domain_admission_containment_unverified",
                    new IllegalStateException("domain_admission_containment_readback_failed")
            );
        }
        return new OperationWorkflowResult(
                OperationWorkflowResult.Status.LIVE_UNKNOWN,
                operation,
                List.of(),
                new IllegalStateException("domain_admission_live_effect_unknown")
        );
    }

    private static OperationWorkflowResult retryable(
            OperationEnvelope operation,
            String code,
            Throwable failure
    ) {
        return new OperationWorkflowResult(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                operation,
                List.of(),
                new IllegalStateException(code, failure)
        );
    }
}
