package com.alechilles.alecstamework.integration.claims;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClaimLookupSessionTest {
    @Test
    void cachesByWorldChunkAndProviderGeneration() {
        ClaimChunkCoordinate chunk = new ClaimChunkCoordinate("world", 0, 0);
        ClaimPopulationKey key = ClaimPopulationKey.simpleClaims("world", UUID.randomUUID());
        CountingBridge bridge = new CountingBridge(ClaimResolution.found(key, new ClaimFootprint(Set.of(chunk).stream().toList())));
        ClaimLookupSession first = new ClaimLookupSession(context(bridge, "instance-a", 1L));

        ClaimResolution firstResult = first.resolveBlock("world", 1.0, 1.0);
        ClaimResolution cachedResult = first.resolveBlock("world", 31.9, 31.9);
        ClaimLookupSession nextGeneration = new ClaimLookupSession(context(bridge, "instance-b", 2L));
        nextGeneration.resolveBlock("world", 1.0, 1.0);

        assertSame(firstResult, cachedResult);
        assertEquals(2L, first.requestCount());
        assertEquals(1L, first.providerCallCount());
        assertEquals(1L, first.cacheHitCount());
        assertEquals(1, first.uniqueChunkCount());
        assertEquals(1L, nextGeneration.providerCallCount());
        assertEquals(2, bridge.calls.get());
    }

    @Test
    void malformedProviderWorldIsAnExplicitError() {
        ClaimPopulationKey wrongWorld = ClaimPopulationKey.simpleClaims("other", UUID.randomUUID());
        CountingBridge bridge = new CountingBridge(ClaimResolution.foundWithoutFootprint(wrongWorld, 1));
        ClaimLookupSession session = new ClaimLookupSession(context(bridge, "instance", 1L));

        ClaimResolution result = session.resolveBlock("world", 0.0, 0.0);

        assertEquals(ClaimLookupResult.Status.ERROR, result.status());
        assertEquals(1L, session.providerCallCount());
    }

    private static ClaimPolicyContext context(ClaimIntegrationBridge bridge,
                                              String instanceToken,
                                              long contractGeneration) {
        return new ClaimPolicyContext(
                "SimpleClaims",
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                bridge.providerId(),
                ClaimProviderState.READY,
                Set.of(ClaimProviderCapability.STABLE_CLAIM_IDENTITY),
                "test",
                null,
                new ClaimProviderGeneration(instanceToken, "loader-" + instanceToken, contractGeneration),
                1L,
                bridge
        );
    }

    private static final class CountingBridge implements ClaimIntegrationBridge {
        private final ClaimResolution resolution;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingBridge(ClaimResolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public String providerId() {
            return "simpleclaims";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getUnavailableReason() {
            return null;
        }

        @Override
        public ClaimResolution resolveClaim(String worldName, double blockX, double blockZ) {
            calls.incrementAndGet();
            return resolution;
        }

        @Override
        public ClaimLookupResult lookupClaim(String worldName, double blockX, double blockZ) {
            return resolution.toLookupResult();
        }
    }
}
