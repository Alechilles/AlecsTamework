package com.alechilles.alecstamework.integration.simpleclaims;

import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Regression coverage for SimpleClaims bridge initialization caching. */
class SimpleClaimsBreedingBridgeTest {
    @AfterEach
    void resetCache() {
        SimpleClaimsBreedingBridge.clearCachedBridgeForTests();
    }

    @Test
    void initializeCachesResolvedBridgeInstance() {
        SimpleClaimsBreedingBridge.clearCachedBridgeForTests();

        SimpleClaimsBreedingBridge first = SimpleClaimsBreedingBridge.initialize();
        SimpleClaimsBreedingBridge second = SimpleClaimsBreedingBridge.initialize();

        assertSame(first, second);
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
