package com.alechilles.alecstamework.integration.simpleclaims;

import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Regression coverage for lifecycle-safe SimpleClaims bridge initialization. */
class SimpleClaimsBreedingBridgeTest {
    @Test
    void initializeDoesNotRetainGenerationBlindStaticBridge() {
        SimpleClaimsBreedingBridge first = SimpleClaimsBreedingBridge.initialize();
        SimpleClaimsBreedingBridge second = SimpleClaimsBreedingBridge.initialize();

        assertNotSame(first, second);
        assertEquals(first.providerId(), second.providerId());
    }

    @Test
    void unavailableSimpleClaimsBridgeReturnsProviderNeutralUnavailableLookup() {
        SimpleClaimsBreedingBridge bridge = SimpleClaimsBreedingBridge.initialize();

        if (bridge.isAvailable()) {
            return;
        }

        assertEquals("simpleclaims", bridge.providerId());
        assertEquals(
                ClaimLookupResult.Status.UNAVAILABLE,
                bridge.lookupClaim("world", 0, 0).status()
        );
    }
}
