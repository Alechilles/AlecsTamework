package com.alechilles.alecstamework.integration.claims;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimProviderRegistryTest {
    @Test
    void offAndInvalidValuesProbeNeitherProvider() {
        FakeProbe questLines = readyProbe(ClaimIntegrationProvider.QUESTLINES_CLAIMS, "questlines-claims", "q1");
        FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
        ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

        ClaimPolicyContext off = registry.resolve("disabled", 11L);
        ClaimPolicyContext invalid = registry.resolve("TownyMaybe", 12L);

        assertEquals(ClaimProviderState.OFF, off.state());
        assertEquals(ClaimIntegrationProvider.OFF, off.requestedProvider());
        assertEquals(11L, off.settingsRevision());
        assertEquals(ClaimProviderState.INVALID, invalid.state());
        assertTrue(invalid.reason().contains("TownyMaybe"));
        assertEquals(0, questLines.probeCount.get());
        assertEquals(0, simpleClaims.probeCount.get());
    }

    @Test
    void explicitProviderProbesOnlyThatProviderAndNeverSubstitutes() {
        FakeProbe questLines = unavailableProbe(
                ClaimIntegrationProvider.QUESTLINES_CLAIMS,
                "questlines-claims",
                ClaimProviderState.ABSENT
        );
        FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
        ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

        ClaimPolicyContext context = registry.resolve("qlc", 7L);

        assertEquals(ClaimProviderState.ABSENT, context.state());
        assertEquals(ClaimIntegrationProvider.QUESTLINES_CLAIMS, context.resolvedProvider());
        assertEquals(1, questLines.probeCount.get());
        assertEquals(0, simpleClaims.probeCount.get());
    }

    @Test
    void explicitSimpleClaimsDoesNotProbeQuestLines() {
        FakeProbe questLines = readyProbe(ClaimIntegrationProvider.QUESTLINES_CLAIMS, "questlines-claims", "q1");
        FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
        ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

        ClaimPolicyContext context = registry.resolveProvider(ClaimIntegrationProvider.SIMPLE_CLAIMS, 3L);

        assertTrue(context.ready());
        assertEquals(ClaimIntegrationProvider.SIMPLE_CLAIMS, context.resolvedProvider());
        assertEquals(0, questLines.probeCount.get());
        assertEquals(1, simpleClaims.probeCount.get());
    }

    @Test
    void autoStopsAfterReadyQuestLines() {
        FakeProbe questLines = readyProbe(ClaimIntegrationProvider.QUESTLINES_CLAIMS, "questlines-claims", "q1");
        FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
        ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

        ClaimPolicyContext context = registry.resolve("Auto", 1L);

        assertSame(questLines.result.get().bridge(), context.bridge());
        assertEquals(ClaimIntegrationProvider.QUESTLINES_CLAIMS, context.resolvedProvider());
        assertEquals(1, questLines.probeCount.get());
        assertEquals(0, simpleClaims.probeCount.get());
    }

    @Test
    void autoFallsBackOnlyWhenQuestLinesIsAbsentOrDisabled() {
        for (ClaimProviderState state : new ClaimProviderState[]{
                ClaimProviderState.ABSENT,
                ClaimProviderState.DISABLED
        }) {
            FakeProbe questLines = unavailableProbe(
                    ClaimIntegrationProvider.QUESTLINES_CLAIMS,
                    "questlines-claims",
                    state
            );
            FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
            ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

            ClaimPolicyContext context = registry.resolve("Auto", 1L);

            assertTrue(context.ready(), state.name());
            assertEquals(ClaimIntegrationProvider.SIMPLE_CLAIMS, context.resolvedProvider(), state.name());
            assertEquals(1, questLines.probeCount.get(), state.name());
            assertEquals(1, simpleClaims.probeCount.get(), state.name());
        }
    }

    @Test
    void autoNeverBypassesInstalledButBrokenQuestLines() {
        for (ClaimProviderState state : new ClaimProviderState[]{
                ClaimProviderState.NOT_READY,
                ClaimProviderState.INCOMPATIBLE,
                ClaimProviderState.ERROR
        }) {
            FakeProbe questLines = unavailableProbe(
                    ClaimIntegrationProvider.QUESTLINES_CLAIMS,
                    "questlines-claims",
                    state
            );
            FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
            ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

            ClaimPolicyContext context = registry.resolve("Auto", 1L);

            assertEquals(state, context.state());
            assertEquals(ClaimIntegrationProvider.QUESTLINES_CLAIMS, context.resolvedProvider());
            assertEquals(1, questLines.probeCount.get());
            assertEquals(0, simpleClaims.probeCount.get(), state.name());
        }
    }

    @Test
    void probeFailureIsAnErrorAndDoesNotTriggerAutoFallback() {
        FakeProbe questLines = readyProbe(ClaimIntegrationProvider.QUESTLINES_CLAIMS, "questlines-claims", "q1");
        questLines.failure = new IllegalStateException("boom");
        FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
        ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

        ClaimPolicyContext context = registry.resolve("Auto", 1L);

        assertEquals(ClaimProviderState.ERROR, context.state());
        assertTrue(context.reason().contains("IllegalStateException"));
        assertEquals(0, simpleClaims.probeCount.get());
    }

    @Test
    void eachOperationCapturesOneSettingsAndProviderGeneration() {
        FakeProbe questLines = readyProbe(ClaimIntegrationProvider.QUESTLINES_CLAIMS, "questlines-claims", "q1");
        FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
        ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

        ClaimPolicyContext first = registry.resolve("Auto", 31L);
        ClaimIntegrationBridge firstBridge = first.bridge();
        questLines.result.set(ready(
                ClaimIntegrationProvider.QUESTLINES_CLAIMS,
                "questlines-claims",
                "q2",
                2L
        ));
        ClaimPolicyContext second = registry.resolve("Auto", 32L);

        assertEquals(31L, first.settingsRevision());
        assertEquals("q1", first.providerGeneration().pluginInstanceToken());
        assertSame(firstBridge, first.bridge());
        assertEquals(32L, second.settingsRevision());
        assertEquals("q2", second.providerGeneration().pluginInstanceToken());
        assertEquals(2L, second.reflectedContractGeneration());
        assertNotSame(first.bridge(), second.bridge());
        assertEquals(2, questLines.probeCount.get());
    }

    @Test
    void settingsAndLifecycleInvalidationReleaseProbeCachesWithoutProbing() {
        FakeProbe questLines = readyProbe(ClaimIntegrationProvider.QUESTLINES_CLAIMS, "questlines-claims", "q1");
        FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
        ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

        registry.onSettingsChanged();
        registry.onPluginLifecycleChanged(ClaimIntegrationProvider.QUESTLINES_CLAIMS);

        assertEquals(2, questLines.invalidateCount.get());
        assertEquals(1, simpleClaims.invalidateCount.get());
        assertEquals(0, questLines.probeCount.get());
        assertEquals(0, simpleClaims.probeCount.get());
    }

    @Test
    void shutdownReleasesBothProbesAndPreventsFurtherResolution() {
        FakeProbe questLines = readyProbe(ClaimIntegrationProvider.QUESTLINES_CLAIMS, "questlines-claims", "q1");
        FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
        ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

        registry.close();
        registry.close();
        ClaimPolicyContext afterClose = registry.resolve("Auto", 50L);

        assertTrue(registry.isClosed());
        assertEquals(1, questLines.closeCount.get());
        assertEquals(1, simpleClaims.closeCount.get());
        assertEquals(ClaimProviderState.ERROR, afterClose.state());
        assertTrue(afterClose.reason().contains("shut down"));
        assertEquals(0, questLines.probeCount.get());
        assertEquals(0, simpleClaims.probeCount.get());
    }

    @Test
    void shutdownStillClosesSecondProbeWhenFirstCloseFails() {
        FakeProbe questLines = readyProbe(ClaimIntegrationProvider.QUESTLINES_CLAIMS, "questlines-claims", "q1");
        questLines.closeFailure = new IllegalStateException("stale loader");
        FakeProbe simpleClaims = readyProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims", "s1");
        ClaimProviderRegistry registry = new ClaimProviderRegistry(questLines, simpleClaims);

        registry.close();

        assertEquals(1, questLines.closeCount.get());
        assertEquals(1, simpleClaims.closeCount.get());
    }

    private static FakeProbe readyProbe(ClaimIntegrationProvider provider, String providerId, String token) {
        return new FakeProbe(provider, ready(provider, providerId, token, 1L));
    }

    private static FakeProbe unavailableProbe(ClaimIntegrationProvider provider,
                                              String providerId,
                                              ClaimProviderState state) {
        return new FakeProbe(
                provider,
                ClaimProviderProbeResult.unavailable(
                        provider,
                        providerId,
                        state,
                        "test",
                        state.name(),
                        ClaimProviderGeneration.NONE
                )
        );
    }

    private static ClaimProviderProbeResult ready(ClaimIntegrationProvider provider,
                                                  String providerId,
                                                  String token,
                                                  long reflectedGeneration) {
        return ClaimProviderProbeResult.ready(
                provider,
                providerId,
                "test",
                new ClaimProviderGeneration(token, "loader-" + token, reflectedGeneration),
                Set.of(
                        ClaimProviderCapability.STABLE_CLAIM_IDENTITY,
                        ClaimProviderCapability.WORLD_SCOPED_EXTENT
                ),
                new FakeBridge(providerId)
        );
    }

    private static final class FakeProbe implements ClaimProviderProbe {
        private final ClaimIntegrationProvider provider;
        private final AtomicReference<ClaimProviderProbeResult> result;
        private final AtomicInteger probeCount = new AtomicInteger();
        private final AtomicInteger invalidateCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private RuntimeException failure;
        private RuntimeException closeFailure;

        private FakeProbe(ClaimIntegrationProvider provider, ClaimProviderProbeResult result) {
            this.provider = provider;
            this.result = new AtomicReference<>(result);
        }

        @Override
        public ClaimIntegrationProvider provider() {
            return provider;
        }

        @Override
        public ClaimProviderProbeResult probe() {
            probeCount.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return result.get();
        }

        @Override
        public void invalidate() {
            invalidateCount.incrementAndGet();
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private record FakeBridge(String providerId) implements ClaimIntegrationBridge {
        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getUnavailableReason() {
            return null;
        }

        @Override
        public ClaimLookupResult lookupClaim(String worldName, double blockX, double blockZ) {
            return ClaimLookupResult.noClaim();
        }
    }
}
