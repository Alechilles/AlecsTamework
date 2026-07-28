package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.TimedDurableOperationWork;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Rechecks a definition-approved UNKNOWN outcome without weakening its containment. */
final class SqliteUnknownLiveOperationRecovery {
    private SqliteUnknownLiveOperationRecovery() {
    }

    static <T> CompletionStage<OperationWorkflowResult> verify(
            OperationEnvelope operation,
            T payload,
            LiveOperationBoundary<T> liveBoundary,
            Supplier<CompletionStage<OperationWorkflowResult>> confirmed,
            String code
    ) {
        return SqliteLiveBoundaryResolver.resolve(
                operation, payload, liveBoundary, code
        ).thenCompose(live -> switch (live.status()) {
            case CONFIRMED -> confirmed.get();
            case COMPENSATE -> failed(
                    OperationWorkflowResult.Status.COMPENSATION_REQUIRED,
                    operation,
                    live.code(),
                    live.cause()
            );
            case RETRYABLE, UNKNOWN -> failed(
                    OperationWorkflowResult.Status.LIVE_RETRYABLE,
                    operation,
                    live.code(),
                    live.cause()
            );
        });
    }

    static <T> TimedDurableOperationWork<T> containmentAwareWork(
            OperationEnvelope operation,
            TimedDurableOperationWork<T> durableWork
    ) {
        if (operation.phase() != OperationPhase.UNKNOWN) {
            return durableWork;
        }
        return (transaction, current, payload, committedAtMs) -> {
            var events = durableWork.execute(
                    transaction, current, payload, committedAtMs
            );
            SqliteUnknownOperationContainmentRecovery.resolve(
                    transaction, current, committedAtMs
            );
            return events;
        };
    }

    private static CompletionStage<OperationWorkflowResult> failed(
            OperationWorkflowResult.Status status,
            OperationEnvelope operation,
            String code,
            Throwable cause
    ) {
        return SqliteOperationResults.completed(
                SqliteOperationResults.failed(
                        status,
                        operation,
                        List.of(),
                        cause == null
                                ? new IllegalStateException(code)
                                : cause
                )
        );
    }
}
