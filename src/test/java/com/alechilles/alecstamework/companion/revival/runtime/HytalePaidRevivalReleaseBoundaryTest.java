package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** No-charge cleanup is bound to the frozen actor world. */
class HytalePaidRevivalReleaseBoundaryTest {

    @Test
    void unavailableActorWorldRetriesWithoutReceiptMutation()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(true);
        AtomicBoolean invoked = new AtomicBoolean();
        HytalePaidRevivalReleaseBoundary boundary =
                new HytalePaidRevivalReleaseBoundary(
                        (world, store, ignoredRequest, operation) -> {
                            invoked.set(true);
                            throw new AssertionError(
                                    "Unavailable world cannot clean receipts"
                            );
                        },
                        new HytaleWorldOperationDispatcher(ignored -> null)
                );

        LiveOperationResult result = boundary.applyOrResolve(
                request,
                PaidRevivalWorldTestFixture.operation(request)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals(
                "paid_revival_release_world_unavailable", result.code()
        );
        assertFalse(invoked.get());
    }
}
