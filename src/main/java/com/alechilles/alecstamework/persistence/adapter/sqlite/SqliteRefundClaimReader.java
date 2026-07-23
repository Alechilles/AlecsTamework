package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.concurrent.CompletionStage;

/** Async exact read of one operation-owned refund recipe. */
final class SqliteRefundClaimReader {
    private static final PersistenceReadKind READ_KIND =
            new PersistenceReadKind("refund_claim_evidence");

    private final SqliteReadExecutor reads;

    SqliteRefundClaimReader(SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException(
                    "Refund claim read executor is required"
            );
        }
        this.reads = reads;
    }

    CompletionStage<PersistenceReadResult<RefundClaim>> find(
            OperationId operationId
    ) {
        if (operationId == null) {
            throw new IllegalArgumentException(
                    "Refund claim operation is required"
            );
        }
        return reads.execute(new SqliteReadCommand<>(
                READ_KIND,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> new SqliteRefundClaimStore(connection)
                        .findByOperation(operationId)
                        .<PersistenceReadResult<RefundClaim>>map(
                                claim -> PersistenceReadResult.found(
                                        claim, 0
                                )
                        )
                        .orElseGet(PersistenceReadResult::absent)
        ));
    }
}
