package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionSpawnPreparationResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalReloadSpawnPreparationRetryTest {
    @Test
    void retriesCanonicalReloadDenialUntilPreparationSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CanonicalReloadSpawnPreparationRetry retry =
                new CanonicalReloadSpawnPreparationRetry(Runnable::run, 4);

        CompanionSpawnPreparationResult result = retry.prepare(() -> {
            int attempt = attempts.incrementAndGet();
            return CompletableFuture.completedFuture(
                    attempt < 3 ? denied(CanonicalReloadSpawnPreparationRetry.TRANSIENT_REASON) : allowed()
            );
        }).get(2, TimeUnit.SECONDS);

        assertTrue(result.allowed());
        assertEquals(3, attempts.get());
    }

    @Test
    void returnsNonTransientDenialWithoutRetrying() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CanonicalReloadSpawnPreparationRetry retry =
                new CanonicalReloadSpawnPreparationRetry(Runnable::run, 4);

        CompanionSpawnPreparationResult result = retry.prepare(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(denied("owner-population-limit"));
        }).get(2, TimeUnit.SECONDS);

        assertFalse(result.allowed());
        assertEquals("owner-population-limit", result.reason());
        assertEquals(1, attempts.get());
    }

    @Test
    void boundsRepeatedCanonicalReloadDenials() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CanonicalReloadSpawnPreparationRetry retry =
                new CanonicalReloadSpawnPreparationRetry(Runnable::run, 3);

        CompanionSpawnPreparationResult result = retry.prepare(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(
                    denied(CanonicalReloadSpawnPreparationRetry.TRANSIENT_REASON)
            );
        }).get(2, TimeUnit.SECONDS);

        assertFalse(result.allowed());
        assertEquals(3, attempts.get());
    }

    private static CompanionSpawnPreparationResult allowed() {
        return new CompanionSpawnPreparationResult(
                true, "spawn-population-prepared", 1, 1, null, null
        );
    }

    private static CompanionSpawnPreparationResult denied(String reason) {
        return new CompanionSpawnPreparationResult(false, reason, 1, 0, null, null);
    }
}
