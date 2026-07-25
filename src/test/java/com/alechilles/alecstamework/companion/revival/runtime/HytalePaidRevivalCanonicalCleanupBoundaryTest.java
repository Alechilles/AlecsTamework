package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Post-canonical receipt cleanup is phase- and world-fenced. */
class HytalePaidRevivalCanonicalCleanupBoundaryTest {

    @Test
    void cleanupModesAuthorizeOnlyTheirDurablePhaseFamily() {
        var noCharge =
                HytalePaidRevivalReceiptCleanupGateway.CleanupMode.NO_CHARGE;
        var canonical =
                HytalePaidRevivalReceiptCleanupGateway.CleanupMode
                        .POST_CANONICAL;

        assertTrue(noCharge.allows(OperationPhase.COMPENSATING));
        assertFalse(noCharge.allows(OperationPhase.DURABLE));
        assertFalse(noCharge.allows(OperationPhase.COMPENSATED));
        assertTrue(canonical.allows(OperationPhase.DURABLE));
        assertTrue(canonical.allows(OperationPhase.COMPENSATED));
        assertFalse(canonical.allows(OperationPhase.COMPENSATING));
    }

    @Test
    void unavailableActorWorldRetriesWithoutCanonicalReceiptMutation()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        AtomicBoolean invoked = new AtomicBoolean();
        HytalePaidRevivalCanonicalCleanupBoundary boundary =
                new HytalePaidRevivalCanonicalCleanupBoundary(
                        (world, store, ignoredRequest, operation) -> {
                            invoked.set(true);
                            throw new AssertionError(
                                    "Unavailable world cannot clean receipts"
                            );
                        },
                        new HytaleWorldOperationDispatcher(ignored -> null)
                );

        LiveOperationResult result = boundary.cleanupAfterDurable(
                request,
                durable(PaidRevivalWorldTestFixture.operation(request))
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals(
                "paid_revival_cleanup_world_unavailable", result.code()
        );
        assertFalse(invoked.get());
    }

    private OperationEnvelope durable(OperationEnvelope source) {
        return new OperationEnvelope(
                source.operationId(),
                source.idempotencyKey(),
                source.kind(),
                source.payloadVersion(),
                source.payloadJson(),
                OperationPhase.DURABLE,
                source.featureScope(),
                source.expectedLifecycleRevision(),
                null,
                0,
                source.attemptCount(),
                null,
                null,
                source.createdAtMs(),
                source.updatedAtMs(),
                source.updatedAtMs(),
                null,
                null,
                source.participants()
        );
    }
}
