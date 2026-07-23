package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Shared exact result and transaction-failure translation for operation workflows. */
final class SqliteOperationResults {
    private SqliteOperationResults() {
    }

    static OperationWorkflowResult failed(
            OperationWorkflowResult.Status status,
            OperationEnvelope operation,
            List<ProjectionEvent> events,
            Throwable failure
    ) {
        return new OperationWorkflowResult(status, operation, events, failure);
    }

    static Throwable transactionFailure(
            PersistenceTransactionResult<?> result,
            String fallback
    ) {
        if (result instanceof PersistenceTransactionResult.RolledBack<?> rolledBack) {
            return cause(rolledBack.failure().cause(), rolledBack.failure().code());
        }
        if (result instanceof PersistenceTransactionResult.Unknown<?> unknown) {
            return cause(unknown.failure().cause(), unknown.failure().code());
        }
        if (result instanceof PersistenceTransactionResult.Rejected<?> rejected) {
            return new IllegalStateException(rejected.reason().name().toLowerCase());
        }
        return new IllegalStateException(fallback);
    }

    static CompletionStage<OperationWorkflowResult> completed(
            OperationWorkflowResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    private static Throwable cause(Throwable cause, String code) {
        return cause == null ? new IllegalStateException(code) : cause;
    }
}
