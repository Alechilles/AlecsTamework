package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import java.util.concurrent.CompletionStage;

/** Executes one external live boundary and normalizes every failure to retryable evidence. */
final class SqliteLiveBoundaryResolver {
    private SqliteLiveBoundaryResolver() {
    }

    static <T> CompletionStage<LiveOperationResult> resolve(
            OperationEnvelope operation,
            T payload,
            LiveOperationBoundary<T> liveBoundary,
            String code
    ) {
        CompletionStage<LiveOperationResult> resolution;
        try {
            resolution = liveBoundary.applyOrResolve(payload, operation);
            if (resolution == null) {
                throw new IllegalStateException(
                        code + "_live_boundary_returned_null"
                );
            }
        } catch (Throwable failure) {
            resolution = retryable(code, failure).completed();
        }
        return resolution.handle((live, failure) -> failure == null
                        ? live
                        : retryable(code, failure))
                .thenApply(live -> live == null
                        ? retryable(
                                code,
                                new IllegalStateException(
                                        code + "_live_boundary_returned_null"
                                )
                        )
                        : live);
    }

    private static LiveOperationResult retryable(
            String code,
            Throwable failure
    ) {
        return LiveOperationResult.retryable(
                code + "_live_boundary_failed", failure
        );
    }
}
