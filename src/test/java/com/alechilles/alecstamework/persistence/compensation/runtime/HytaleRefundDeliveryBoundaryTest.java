package com.alechilles.alecstamework.persistence.compensation.runtime;

import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundItem;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The production boundary dispatches only to the immutable claim world. */
class HytaleRefundDeliveryBoundaryTest {
    private static final OperationId OPERATION = OperationId.parse(
            "10000000-0000-0000-0000-000000000001"
    );

    @Test
    void unavailableExactWorldIsRetryableWithoutInventoryAccess()
            throws Exception {
        AtomicReference<String> lookedUp = new AtomicReference<>();
        HytaleRefundDeliveryBoundary boundary =
                new HytaleRefundDeliveryBoundary(
                        worldKey -> {
                            lookedUp.set(worldKey);
                            return null;
                        },
                        (world, store, claim, operation) -> {
                            throw new AssertionError(
                                    "Unavailable world cannot access inventory"
                            );
                        }
                );

        LiveOperationResult result = boundary.applyOrResolve(
                claim(),
                operation(OPERATION)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                LiveOperationResult.Status.RETRYABLE,
                result.status()
        );
        assertEquals("refund_world_unavailable", result.code());
        assertEquals("recipient-world", lookedUp.get());
    }

    @Test
    void mismatchedOperationFailsClosedBeforeWorldLookup()
            throws Exception {
        HytaleRefundDeliveryBoundary boundary =
                new HytaleRefundDeliveryBoundary(
                        ignored -> {
                            throw new AssertionError(
                                    "Mismatched claim cannot reach world lookup"
                            );
                        },
                        (world, store, claim, operation) -> {
                            throw new AssertionError(
                                    "Mismatched claim cannot reach inventory"
                            );
                        }
                );
        OperationId different = OperationId.parse(
                "10000000-0000-0000-0000-000000000002"
        );

        LiveOperationResult result = boundary.applyOrResolve(
                claim(),
                operation(different)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                LiveOperationResult.Status.UNKNOWN,
                result.status()
        );
        assertEquals("refund_operation_mismatch", result.code());
    }

    @Test
    void durableDeliveryEvidenceConfirmsWithoutRequiringOnlinePlayer()
            throws Exception {
        HytaleRefundDeliveryBoundary boundary =
                new HytaleRefundDeliveryBoundary(
                        ignored -> {
                            throw new AssertionError(
                                    "Delivered claim cannot reach world lookup"
                            );
                        },
                        (world, store, claim, operation) -> {
                            throw new AssertionError(
                                    "Delivered claim cannot reach inventory"
                            );
                        }
                );
        RefundClaim delivered = claim().delivered(
                "durable-refund-evidence",
                -500
        );

        LiveOperationResult result = boundary.applyOrResolve(
                delivered,
                operation(OPERATION)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                LiveOperationResult.Status.CONFIRMED,
                result.status()
        );
        assertEquals("durable-refund-evidence", result.code());
    }

    private RefundClaim claim() {
        return new RefundClaim(
                OPERATION,
                UUID.fromString(
                        "20000000-0000-0000-0000-000000000001"
                ),
                "recipient-world",
                List.of(new RefundItem("Ingredient_Stick", 3)),
                "capture_source",
                "refund:" + OPERATION,
                -700,
                null,
                null
        );
    }

    private OperationEnvelope operation(OperationId operationId) {
        return new OperationEnvelope(
                operationId,
                new IdempotencyKey("refund-boundary-test"),
                new OperationKind("refund_test"),
                1,
                "{}",
                OperationPhase.COMPENSATING,
                "refund-boundary-test",
                null,
                null,
                0,
                0,
                null,
                null,
                -700,
                -600,
                null,
                null,
                null,
                List.of(OperationScope.operation(operationId))
        );
    }
}
