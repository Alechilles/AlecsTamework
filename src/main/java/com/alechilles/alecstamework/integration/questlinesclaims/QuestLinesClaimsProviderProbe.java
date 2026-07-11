package com.alechilles.alecstamework.integration.questlinesclaims;

import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocation;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocator;
import com.alechilles.alecstamework.integration.claims.ClaimProviderCapability;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbe;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbeResult;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.claims.HytaleClaimPluginLocator;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/** Lifecycle-aware QuestLines Claims 1.3.1 contract probe. */
public final class QuestLinesClaimsProviderProbe implements ClaimProviderProbe {
    private static final String PROVIDER_ID = "questlines-claims";
    private static final String SUPPORTED_VERSION = "1.3.1";

    private final ClaimPluginLocator locator;
    private ClaimProviderGeneration cachedLocationGeneration = ClaimProviderGeneration.NONE;
    private ClaimProviderProbeResult cachedReady;
    private long reflectedGeneration;

    public QuestLinesClaimsProviderProbe() {
        this(new HytaleClaimPluginLocator(
                PROVIDER_ID,
                HytaleClaimPluginLocator.QUESTLINES_PLUGIN_IDENTIFIER
        ));
    }

    QuestLinesClaimsProviderProbe(@Nonnull ClaimPluginLocator locator) {
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    @Nonnull
    @Override
    public ClaimIntegrationProvider provider() {
        return ClaimIntegrationProvider.QUESTLINES_CLAIMS;
    }

    @Nonnull
    @Override
    public synchronized ClaimProviderProbeResult probe() {
        ClaimPluginLocation location = locator.locate();
        if (location.state() != ClaimProviderState.READY || location.pluginInstance() == null) {
            clearCachedContract();
            return unavailable(location, location.state(), location.reason());
        }
        if (!supported(location.pluginVersion())) {
            clearCachedContract();
            return unavailable(
                    location,
                    ClaimProviderState.INCOMPATIBLE,
                    "QuestLines Claims version " + displayVersion(location.pluginVersion())
                            + " is unsupported; expected 1.3.1."
            );
        }
        if (cachedReady != null && cachedLocationGeneration.equals(location.generation())) {
            return cachedReady;
        }
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forPlugin(location.pluginInstance());
        if (!bridge.isAvailable()) {
            clearCachedContract();
            return unavailable(location, ClaimProviderState.INCOMPATIBLE, bridge.getUnavailableReason());
        }
        cachedLocationGeneration = location.generation();
        reflectedGeneration = increment(reflectedGeneration);
        ClaimProviderGeneration generation = reflectedGeneration(location, reflectedGeneration);
        cachedReady = ClaimProviderProbeResult.ready(
                provider(),
                PROVIDER_ID,
                location.pluginVersion(),
                generation,
                Set.of(
                        ClaimProviderCapability.STABLE_CLAIM_IDENTITY,
                        ClaimProviderCapability.WORLD_SCOPED_EXTENT
                ),
                bridge
        );
        return cachedReady;
    }

    @Override
    public synchronized void invalidate() {
        clearCachedContract();
    }

    @Override
    public synchronized void close() {
        clearCachedContract();
        locator.close();
    }

    private void clearCachedContract() {
        cachedReady = null;
        cachedLocationGeneration = ClaimProviderGeneration.NONE;
    }

    @Nonnull
    private ClaimProviderProbeResult unavailable(ClaimPluginLocation location,
                                                   ClaimProviderState state,
                                                   String reason) {
        return ClaimProviderProbeResult.unavailable(
                provider(),
                PROVIDER_ID,
                state,
                location.pluginVersion(),
                reason,
                location.generation()
        );
    }

    private static boolean supported(String version) {
        return version != null
                && (version.equals(SUPPORTED_VERSION)
                || version.startsWith(SUPPORTED_VERSION + "+")
                || version.startsWith(SUPPORTED_VERSION + "-"));
    }

    private static String displayVersion(String version) {
        return version == null || version.isBlank() ? "<unknown>" : version;
    }

    private static ClaimProviderGeneration reflectedGeneration(ClaimPluginLocation location,
                                                                long reflectedGeneration) {
        return new ClaimProviderGeneration(
                location.generation().pluginInstanceToken(),
                location.generation().classLoaderToken(),
                reflectedGeneration
        );
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? 1L : value + 1L;
    }
}
