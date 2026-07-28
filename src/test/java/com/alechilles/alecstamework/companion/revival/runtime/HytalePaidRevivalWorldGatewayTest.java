package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** World-gateway normalization without constructing stale Hytale ECS state. */
class HytalePaidRevivalWorldGatewayTest {

    @Test
    void currentWorldThreadDelegatesOneExactAttempt() throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(true);
        AtomicBoolean asserted = new AtomicBoolean();

        PaidRevivalLiveResult result =
                HytalePaidRevivalWorldGateway.executeOnWorldThread(
                        new PaidRevivalWorldExecutor(),
                        request,
                        PaidRevivalWorldTestFixture.operation(request),
                        () -> asserted.set(true),
                        FakePaidRevivalWorldAttempts::emptyCost
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(PaidRevivalLiveResult.Status.CONFIRMED, result.status());
        assertEquals(true, asserted.get());
    }

    @Test
    void failedThreadAssertionNeverOpensInventoryAttempt()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        AtomicBoolean opened = new AtomicBoolean();

        PaidRevivalLiveResult result =
                HytalePaidRevivalWorldGateway.executeOnWorldThread(
                        new PaidRevivalWorldExecutor(),
                        request,
                        PaidRevivalWorldTestFixture.operation(request),
                        () -> {
                            throw new IllegalStateException("wrong thread");
                        },
                        () -> {
                            opened.set(true);
                            return new FakePaidRevivalWorldAttempts();
                        }
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(PaidRevivalLiveResult.Status.UNKNOWN, result.status());
        assertEquals(
                "paid_revival_world_thread_unavailable", result.code()
        );
        assertFalse(opened.get());
    }
}
