package com.alechilles.alecstamework.integration.questlinesclaims;

import com.alechilles.alecstamework.integration.claims.ClaimPluginLocation;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocator;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbeResult;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import java.util.concurrent.atomic.AtomicReference;
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

    private static ClaimPluginLocation ready(String pluginToken, Object plugin) {
        return new ClaimPluginLocation(
                "questlines-claims",
                ClaimProviderState.READY,
                "1.3.1",
                null,
                generation(pluginToken),
                plugin
        );
    }

    private static ClaimProviderGeneration generation(String pluginToken) {
        return new ClaimProviderGeneration(pluginToken, pluginToken + "-loader", 0L);
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
