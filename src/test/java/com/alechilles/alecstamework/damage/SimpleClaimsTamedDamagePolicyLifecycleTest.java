package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that the production policy owns and releases its reflected optional-plugin resolver. */
class SimpleClaimsTamedDamagePolicyLifecycleTest {
    @Test
    void settingsInvalidationAndCloseAreForwardedExactlyOnce() {
        TrackingResolver resolver = new TrackingResolver();
        SimpleClaimsTamedDamagePolicy policy = new SimpleClaimsTamedDamagePolicy(
                new TamedDamageTargetEligibilityResolver(),
                resolver,
                (player, permission) -> false,
                (category, message) -> {
                }
        );

        policy.onRuntimeSettingsChanged();
        policy.close();
        policy.close();
        policy.onRuntimeSettingsChanged();

        assertEquals(1, resolver.invalidations.get());
        assertEquals(1, resolver.closes.get());
    }

    private static final class TrackingResolver implements SimpleClaimsDamageCapabilityResolver {
        private final AtomicInteger invalidations = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public Resolution resolve() {
            return Resolution.unavailable(
                    ClaimProviderState.ABSENT,
                    ClaimProviderGeneration.NONE,
                    null,
                    "not installed"
            );
        }

        @Override
        public void invalidate() {
            invalidations.incrementAndGet();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
