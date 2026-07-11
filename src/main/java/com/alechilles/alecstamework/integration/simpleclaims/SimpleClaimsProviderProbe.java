package com.alechilles.alecstamework.integration.simpleclaims;

import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocation;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocator;
import com.alechilles.alecstamework.integration.claims.ClaimProviderCapability;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbe;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbeResult;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.claims.HytaleClaimPluginLocator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nonnull;

/** Lifecycle-aware SimpleClaims 1.0.38-compatible capability probe. */
public final class SimpleClaimsProviderProbe implements ClaimProviderProbe {
    private static final String PROVIDER_ID = "simpleclaims";
    private static final String PLUGIN_IDENTIFIER = "Buuz135:SimpleClaims";

    private final ClaimPluginLocator locator;
    private final Function<Object, SimpleClaimsBreedingBridge> bridgeFactory;
    private ClaimProviderGeneration cachedLocationGeneration = ClaimProviderGeneration.NONE;
    private ClaimProviderProbeResult cachedReady;
    private long reflectedGeneration;

    public SimpleClaimsProviderProbe() {
        this(
                new HytaleClaimPluginLocator(PROVIDER_ID, PLUGIN_IDENTIFIER),
                plugin -> SimpleClaimsBreedingBridge.forClassLoader(plugin.getClass().getClassLoader())
        );
    }

    SimpleClaimsProviderProbe(@Nonnull ClaimPluginLocator locator) {
        this(locator, plugin ->
                SimpleClaimsBreedingBridge.forClassLoader(plugin.getClass().getClassLoader()));
    }

    SimpleClaimsProviderProbe(
            @Nonnull ClaimPluginLocator locator,
            @Nonnull Function<Object, SimpleClaimsBreedingBridge> bridgeFactory
    ) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.bridgeFactory = Objects.requireNonNull(bridgeFactory, "bridgeFactory");
    }

    @Nonnull
    @Override
    public ClaimIntegrationProvider provider() {
        return ClaimIntegrationProvider.SIMPLE_CLAIMS;
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
                    "SimpleClaims version " + displayVersion(location.pluginVersion())
                            + " is unsupported; expected >=1.0.38 and <1.1.0."
            );
        }
        if (cachedReady != null && cachedLocationGeneration.equals(location.generation())) {
            return cachedReady;
        }
        SimpleClaimsBreedingBridge bridge = bridgeFactory.apply(location.pluginInstance());
        if (!bridge.isAvailable()) {
            clearCachedContract();
            return unavailable(location, ClaimProviderState.INCOMPATIBLE, bridge.getUnavailableReason());
        }
        EnumSet<ClaimProviderCapability> capabilities = EnumSet.of(
                ClaimProviderCapability.STABLE_CLAIM_IDENTITY
        );
        if (bridge.isExtentAvailable()) {
            capabilities.add(ClaimProviderCapability.WORLD_SCOPED_EXTENT);
        }
        if (bridge.isDamagePolicyAvailable()) {
            capabilities.add(ClaimProviderCapability.DAMAGE_ACCESS);
        }
        cachedLocationGeneration = location.generation();
        reflectedGeneration = increment(reflectedGeneration);
        cachedReady = ClaimProviderProbeResult.ready(
                provider(),
                PROVIDER_ID,
                location.pluginVersion(),
                reflectedGeneration(location, reflectedGeneration),
                capabilities,
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
        if (version == null) {
            return false;
        }
        String core = version.trim().split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        if (parts.length < 3) {
            return false;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            return major == 1 && minor == 0 && patch >= 38;
        } catch (NumberFormatException ignored) {
            return false;
        }
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
