package com.alechilles.alecstamework.integration.claims;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests independent claim-warning throttle keys with a deterministic monotonic clock. */
class ClaimWarningThrottleTest {
    private static final long INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1L);

    @Test
    void independentCategoryProviderAndContextKeysDoNotSuppressOneAnother() {
        AtomicLong now = new AtomicLong(123L);
        ClaimWarningThrottle throttle = new ClaimWarningThrottle(INTERVAL_NANOS, now::get);

        assertTrue(throttle.tryAcquire("lookup-error", "questlines-claims", "world-a"));
        assertTrue(throttle.tryAcquire("population-count-error", "questlines-claims", "world-a"));
        assertTrue(throttle.tryAcquire("lookup-error", "simpleclaims", "world-a"));
        assertTrue(throttle.tryAcquire("lookup-error", "questlines-claims", "world-b"));
    }

    @Test
    void repeatedSameKeyIsThrottledUntilIntervalExpires() {
        AtomicLong now = new AtomicLong(-500L);
        ClaimWarningThrottle throttle = new ClaimWarningThrottle(INTERVAL_NANOS, now::get);

        assertTrue(throttle.tryAcquire("provider-unavailable", "questlines-claims", "tame-admission"));
        assertFalse(throttle.tryAcquire("provider-unavailable", "questlines-claims", "tame-admission"));

        now.addAndGet(INTERVAL_NANOS - 1L);
        assertFalse(throttle.tryAcquire("provider-unavailable", "questlines-claims", "tame-admission"));

        now.incrementAndGet();
        assertTrue(throttle.tryAcquire("provider-unavailable", "questlines-claims", "tame-admission"));
    }
}
