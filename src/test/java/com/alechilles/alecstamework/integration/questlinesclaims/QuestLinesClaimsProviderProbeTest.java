package com.alechilles.alecstamework.integration.questlinesclaims;

import com.alechilles.alecstamework.integration.claims.ClaimPluginLocation;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocator;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbeResult;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestLinesClaimsProviderProbeTest {
    @Test
    void readyContractIsCachedOnlyForOnePluginGeneration() {
        FakeLocator locator = new FakeLocator(ready("plugin-a", new FixturePlugin()));
        QuestLinesClaimsProviderProbe probe = new QuestLinesClaimsProviderProbe(locator);

        ClaimProviderProbeResult first = probe.probe();
        ClaimProviderProbeResult repeated = probe.probe();
        locator.location.set(ready("plugin-b", new FixturePlugin()));
        ClaimProviderProbeResult replacement = probe.probe();

        assertEquals(ClaimProviderState.READY, first.state());
        assertSame(first, repeated);
        assertEquals(1L, first.generation().reflectedContractGeneration());
        assertEquals(2L, replacement.generation().reflectedContractGeneration());
    }

    @Test
    void unsupportedInstalledVersionFailsClosed() {
        FakeLocator locator = new FakeLocator(new ClaimPluginLocation(
                "questlines-claims",
                ClaimProviderState.READY,
                "1.4.0",
                null,
                generation("plugin-a"),
                new FixturePlugin()
        ));

        ClaimProviderProbeResult result = new QuestLinesClaimsProviderProbe(locator).probe();

        assertEquals(ClaimProviderState.INCOMPATIBLE, result.state());
        assertTrue(result.reason().contains("1.3.1"));
    }

    @Test
    void exactReleaseAllowsBuildMetadataButRejectsUnverifiedPrereleases() {
        ClaimProviderProbeResult build = new QuestLinesClaimsProviderProbe(
                new FakeLocator(ready("plugin-build", "1.3.1+vendor.7", new FixturePlugin()))
        ).probe();

        assertEquals(ClaimProviderState.READY, build.state());
        for (String version : new String[]{"1.3.1-rc.1", "1.3.1-beta+vendor.7"}) {
            ClaimProviderProbeResult prerelease = new QuestLinesClaimsProviderProbe(
                    new FakeLocator(ready("plugin-prerelease", version, new FixturePlugin()))
            ).probe();
            assertEquals(
                    ClaimProviderState.INCOMPATIBLE,
                    prerelease.state(),
                    () -> "Unverified prerelease should be rejected: " + version
            );
        }
    }

    @Test
    void pluginLocatorDoesNotRunUnderProbeMonitor() throws Exception {
        CountDownLatch locateEntered = new CountDownLatch(1);
        CountDownLatch releaseLocate = new CountDownLatch(1);
        QuestLinesClaimsProviderProbe probe = new QuestLinesClaimsProviderProbe(() -> {
            locateEntered.countDown();
            awaitUnchecked(releaseLocate);
            return ready("plugin-a", new FixturePlugin());
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var probing = executor.submit(probe::probe);
            assertTrue(locateEntered.await(1, TimeUnit.SECONDS));
            executor.submit(probe::invalidate).get(1, TimeUnit.SECONDS);
            releaseLocate.countDown();
            assertEquals(ClaimProviderState.READY, probing.get(1, TimeUnit.SECONDS).state());
        } finally {
            releaseLocate.countDown();
            executor.shutdownNow();
        }
    }

    private static ClaimPluginLocation ready(String pluginToken, Object plugin) {
        return ready(pluginToken, "1.3.1", plugin);
    }

    private static ClaimPluginLocation ready(String pluginToken, String version, Object plugin) {
        return new ClaimPluginLocation(
                "questlines-claims",
                ClaimProviderState.READY,
                version,
                null,
                generation(pluginToken),
                plugin
        );
    }

    private static ClaimProviderGeneration generation(String pluginToken) {
        return new ClaimProviderGeneration(pluginToken, pluginToken + "-loader", 0L);
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

    public static final class FixturePlugin {
        public FixtureApi getApi() {
            return new FixtureApi();
        }
    }

    public static final class FixtureApi {
        public Object getClaimAtBlock(String worldName, int blockX, int blockZ) {
            return null;
        }
    }
}
