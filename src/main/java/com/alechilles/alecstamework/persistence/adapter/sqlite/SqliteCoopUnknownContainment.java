package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Shared exact-scope containment for unknown coop live effects. */
final class SqliteCoopUnknownContainment {
    private final SqliteOperationEngine operations;
    private final LongSupplier clock;

    SqliteCoopUnknownContainment(
            SqliteOperationEngine operations,
            LongSupplier clock
    ) {
        this.operations = operations;
        this.clock = clock;
    }

    CompletionStage<OperationWorkflowResult> containIfUnknown(
            OperationWorkflowResult result,
            ProfileId profileId,
            CoopSlotKey slotKey,
            String summary
    ) {
        if (result.status() != OperationWorkflowResult.Status.LIVE_UNKNOWN
                || result.operation() == null) {
            return CompletableFuture.completedFuture(result);
        }
        OperationEnvelope operation = result.operation();
        return operations.containUnknown(
                operation,
                operation.failureCode() == null
                        ? "coop_live_outcome_unknown"
                        : operation.failureCode(),
                summary,
                List.of(
                        OperationScope.operation(operation.operationId()),
                        OperationScope.profile(profileId),
                        OperationScope.coop(slotKey.toString())
                ),
                clock.getAsLong()
        ).completion().thenApply(containment -> {
            if (containment instanceof PersistenceTransactionResult.Committed<?>) {
                return result;
            }
            return new OperationWorkflowResult(
                    OperationWorkflowResult.Status.LIVE_UNKNOWN,
                    operation,
                    List.of(),
                    new IllegalStateException(
                            "coop_unknown_containment_failed",
                            result.failure()
                    )
            );
        });
    }
}
