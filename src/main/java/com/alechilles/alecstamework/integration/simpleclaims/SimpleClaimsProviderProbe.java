package com.alechilles.alecstamework.integration.simpleclaims;

import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocation;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocator;
import com.alechilles.alecstamework.integration.claims.ClaimPluginVersionCompatibility;
import com.alechilles.alecstamework.integration.claims.ClaimProviderCapability;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbe;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbeResult;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.claims.HytaleClaimPluginLocator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.annotation.Nonnull;

/** Lifecycle-aware SimpleClaims 1.0.38-compatible capability probe. */
public final class SimpleClaimsProviderProbe implements ClaimProviderProbe {
    private static final String PROVIDER_ID = "simpleclaims";
    private static final String PLUGIN_IDENTIFIER = "Buuz135:SimpleClaims";

    private final ClaimPluginLocator locator;
    private final Function<Object, SimpleClaimsBreedingBridge> bridgeFactory;
    private final AtomicLong observationSequence = new AtomicLong();
    private ClaimProviderGeneration cachedLocationGeneration = ClaimProviderGeneration.NONE;
    private ClaimProviderProbeResult cachedReady;
    private long reflectedGeneration;
    private long cacheEpoch;
    private long latestPublishedObservation;
    private boolean closed;

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
    public ClaimProviderProbeResult probe() {
        long observation = observationSequence.incrementAndGet();
        ClaimPluginLocation location = locate();
        if (location.state() != ClaimProviderState.READY || location.pluginInstance() == null) {
            clearObservedContract(observation);
            return unavailable(location, location.state(), location.reason());
        }
        if (!supported(location.pluginVersion())) {
            clearObservedContract(observation);
            return unavailable(
                    location,
                    ClaimProviderState.INCOMPATIBLE,
                    "SimpleClaims version " + displayVersion(location.pluginVersion())
                            + " is unsupported; expected >=1.0.38 and <1.1.0."
            );
        }
        Snapshot snapshot = snapshot(observation, location.generation());
        if (snapshot.cachedReady() != null) {
            return snapshot.cachedReady();
        }
        SimpleClaimsBreedingBridge bridge;
        try {
            bridge = bridgeFactory.apply(location.pluginInstance());
        } catch (Throwable throwable) {
            clearObservedContract(observation);
            return unavailable(
                    location,
                    ClaimProviderState.INCOMPATIBLE,
                    "SimpleClaims contract reflection failed: " + message(throwable)
            );
        }
        if (!bridge.isAvailable()) {
            clearObservedContract(observation);
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
        return publishReady(observation, snapshot.cacheEpoch(), location, capabilities, bridge);
    }

    @Override
    public void invalidate() {
        synchronized (this) {
            cacheEpoch = increment(cacheEpoch);
            clearCachedContract();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            cacheEpoch = increment(cacheEpoch);
            clearCachedContract();
        }
        locator.close();
    }

    private void clearCachedContract() {
        cachedReady = null;
        cachedLocationGeneration = ClaimProviderGeneration.NONE;
    }

    @Nonnull
    private ClaimPluginLocation locate() {
        try {
            return locator.locate();
        } catch (Throwable throwable) {
            return new ClaimPluginLocation(
                    PROVIDER_ID,
                    ClaimProviderState.ERROR,
                    null,
                    "SimpleClaims plugin lookup failed: " + message(throwable),
                    ClaimProviderGeneration.NONE,
                    null
            );
        }
    }

    @Nonnull
    private synchronized Snapshot snapshot(long observation,
                                           @Nonnull ClaimProviderGeneration locationGeneration) {
        if (closed) {
            return new Snapshot(cacheEpoch, null);
        }
        if (cachedReady != null && cachedLocationGeneration.equals(locationGeneration)) {
            latestPublishedObservation = Math.max(latestPublishedObservation, observation);
            return new Snapshot(cacheEpoch, cachedReady);
        }
        return new Snapshot(cacheEpoch, null);
    }

    @Nonnull
    private synchronized ClaimProviderProbeResult publishReady(
            long observation,
            long observedEpoch,
            @Nonnull ClaimPluginLocation location,
            @Nonnull EnumSet<ClaimProviderCapability> capabilities,
            @Nonnull SimpleClaimsBreedingBridge bridge) {
        long contractGeneration = increment(reflectedGeneration);
        reflectedGeneration = contractGeneration;
        ClaimProviderProbeResult candidate = ClaimProviderProbeResult.ready(
                provider(),
                PROVIDER_ID,
                location.pluginVersion(),
                reflectedGeneration(location, contractGeneration),
                capabilities,
                bridge
        );
        if (!closed
                && cacheEpoch == observedEpoch
                && observation >= latestPublishedObservation) {
            cachedLocationGeneration = location.generation();
            cachedReady = candidate;
            latestPublishedObservation = observation;
            return cachedReady;
        }
        return candidate;
    }

    private synchronized void clearObservedContract(long observation) {
        if (!closed && observation >= latestPublishedObservation) {
            clearCachedContract();
            latestPublishedObservation = observation;
        }
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
        return ClaimPluginVersionCompatibility.supportsSimpleClaims(version);
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

    @Nonnull
    private static String message(@Nonnull Throwable throwable) {
        String detail = throwable.getMessage();
        return detail == null || detail.isBlank() ? throwable.getClass().getSimpleName() : detail;
    }

    private record Snapshot(long cacheEpoch, @javax.annotation.Nullable ClaimProviderProbeResult cachedReady) {
    }
}
