package com.alechilles.alecstamework.integration.simpleclaims;

import com.alechilles.alecstamework.integration.claims.ClaimPluginLocation;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocator;
import com.alechilles.alecstamework.integration.claims.ClaimProviderCapability;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbeResult;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleClaimsProviderProbeTest {
    @BeforeEach
    void resetFixture() {
        SimpleClaimsCapabilitiesTest.FixtureClaimManager.reset();
    }

    @Test
    void capabilitiesAndReflectionAreCachedByPluginGeneration() {
        FakeLocator locator = new FakeLocator(ready("plugin-a", "1.0.38"));
        SimpleClaimsProviderProbe probe = new SimpleClaimsProviderProbe(
                locator,
                ignored -> fixtureBridge()
        );

        ClaimProviderProbeResult first = probe.probe();
        ClaimProviderProbeResult repeated = probe.probe();
        locator.location.set(ready("plugin-b", "1.0.39"));
        ClaimProviderProbeResult replacement = probe.probe();

        assertEquals(ClaimProviderState.READY, first.state());
        assertSame(first, repeated);
        assertTrue(first.capabilities().contains(ClaimProviderCapability.STABLE_CLAIM_IDENTITY));
        assertTrue(first.capabilities().contains(ClaimProviderCapability.WORLD_SCOPED_EXTENT));
        assertTrue(first.capabilities().contains(ClaimProviderCapability.DAMAGE_ACCESS));
        assertEquals(1L, first.generation().reflectedContractGeneration());
        assertEquals(2L, replacement.generation().reflectedContractGeneration());
    }

    @Test
    void unsupportedMinorVersionIsIncompatible() {
        ClaimProviderProbeResult result = new SimpleClaimsProviderProbe(
                new FakeLocator(ready("plugin-a", "1.1.0")),
                ignored -> fixtureBridge()
        ).probe();

        assertEquals(ClaimProviderState.INCOMPATIBLE, result.state());
        assertTrue(result.reason().contains("<1.1.0"));
    }

    @Test
    void reflectionDoesNotHoldProbeMonitor() throws Exception {
        CountDownLatch reflectionEntered = new CountDownLatch(1);
        CountDownLatch releaseReflection = new CountDownLatch(1);
        SimpleClaimsProviderProbe probe = new SimpleClaimsProviderProbe(
                new FakeLocator(ready("plugin-a", "1.0.38")),
                ignored -> {
                    reflectionEntered.countDown();
                    awaitUnchecked(releaseReflection);
                    return fixtureBridge();
                }
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var probing = executor.submit(probe::probe);
            assertTrue(reflectionEntered.await(1, TimeUnit.SECONDS));
            executor.submit(probe::invalidate).get(1, TimeUnit.SECONDS);
            releaseReflection.countDown();
            assertEquals(ClaimProviderState.READY, probing.get(1, TimeUnit.SECONDS).state());
        } finally {
            releaseReflection.countDown();
            executor.shutdownNow();
        }
    }

    private static SimpleClaimsBreedingBridge fixtureBridge() {
        return SimpleClaimsBreedingBridge.forTypesForTests(
                SimpleClaimsCapabilitiesTest.FixtureClaimManager.class,
                SimpleClaimsCapabilitiesTest.FixtureChunk.class,
                SimpleClaimsCapabilitiesTest.FixtureParty.class
        );
    }

    private static ClaimPluginLocation ready(String pluginToken, String version) {
        return new ClaimPluginLocation(
                "simpleclaims",
                ClaimProviderState.READY,
                version,
                null,
                new ClaimProviderGeneration(pluginToken, pluginToken + "-loader", 0L),
                new Object()
        );
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static final class FakeLocator implements ClaimPluginLocator {
        private final AtomicReference<ClaimPluginLocation> location;

        private FakeLocator(ClaimPluginLocation location) {
            this.location = new AtomicReference<>(location);
        }

        @Override
        public ClaimPluginLocation locate() {
            return location.get();
        }
    }
}
