package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundItem;
import com.alechilles.alecstamework.persistence.operation.OperationId;

/** Exact refund-recipe construction shared by paid revival recovery checks. */
final class SqlitePaidRevivalRefunds {
    private static final String REASON = "paid_revival_spawn_absent";

    private SqlitePaidRevivalRefunds() {
    }

    static RefundClaim claim(
            OperationId operationId,
            PaidRevivalRequest request
    ) {
        if (request.exactCost().isEmpty()) {
            throw new IllegalStateException(
                    "paid_revival_empty_charge_cannot_require_refund"
            );
        }
        return new RefundClaim(
                operationId,
                request.familyKey().ownerId().value(),
                request.exactCost().stream()
                        .map(item -> new RefundItem(
                                item.itemId(), item.quantity()
                        ))
                        .toList(),
                REASON,
                "paid-revival-refund:" + operationId,
                request.requestedAtMs(),
                null,
                null
        );
    }

    static boolean same(
            RefundClaim expected,
            RefundClaim actual
    ) {
        return expected.operationId().equals(actual.operationId())
                && expected.recipientUuid().equals(actual.recipientUuid())
                && expected.items().equals(actual.items())
                && expected.reasonCode().equals(actual.reasonCode())
                && expected.receiptKey().equals(actual.receiptKey())
                && expected.claimedAtMs() == actual.claimedAtMs();
    }
}
