package com.alechilles.alecstamework.integration.simpleclaims;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
}
