package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.claims.ClaimPluginLocation;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocator;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.claims.HytaleClaimPluginLocator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Positive-generation cache for SimpleClaims damage and identity access.
 *
 * <p>Every resolve observes PluginManager first. Absent, disabled, failed, and incompatible states
 * are never sticky. Locator and reflection work occur outside the registry monitor, and an older
 * in-flight observation cannot replace a newer published generation.</p>
 */
final class SimpleClaimsDamageCapabilityRegistry
        implements SimpleClaimsDamageCapabilityResolver, AutoCloseable {
    private static final String PROVIDER_ID = "simpleclaims-damage";
    private static final String PLUGIN_IDENTIFIER = "Buuz135:SimpleClaims";

    private final ClaimPluginLocator locator;
    private final Function<Object, SimpleClaimsDamageGeneration> generationFactory;
    private final AtomicLong observationSequence = new AtomicLong();
    private ClaimProviderGeneration cachedLocationGeneration = ClaimProviderGeneration.NONE;
    private Resolution cachedReady;
    private long reflectedGeneration;
    private long cacheEpoch;
    private long latestPublishedObservation;
    private boolean closed;

    SimpleClaimsDamageCapabilityRegistry() {
        this(
                new HytaleClaimPluginLocator(PROVIDER_ID, PLUGIN_IDENTIFIER),
                SimpleClaimsDamageGeneration::reflect
        );
    }

    SimpleClaimsDamageCapabilityRegistry(@Nonnull ClaimPluginLocator locator,
                                         @Nonnull Function<Object, SimpleClaimsDamageGeneration> generationFactory) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.generationFactory = Objects.requireNonNull(generationFactory, "generationFactory");
    }

    @Nonnull
    @Override
    public Resolution resolve() {
        long observation = observationSequence.incrementAndGet();
        ClaimPluginLocation location = locate();
        if (location.state() != ClaimProviderState.READY || location.pluginInstance() == null) {
            clearObserved(observation);
            return unavailable(location, location.state(), location.reason());
        }
        if (!supported(location.pluginVersion())) {
            clearObserved(observation);
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

        SimpleClaimsDamageGeneration capability;
        try {
            capability = generationFactory.apply(location.pluginInstance());
        } catch (Throwable throwable) {
            clearObserved(observation);
            return unavailable(
                    location,
                    ClaimProviderState.INCOMPATIBLE,
                    "SimpleClaims damage reflection failed: " + message(throwable)
            );
        }
        if (capability == null || !capability.usable()) {
            clearObserved(observation);
            return unavailable(
                    location,
                    ClaimProviderState.INCOMPATIBLE,
                    capability == null
                            ? "SimpleClaims damage reflection returned no capability."
                            : capability.unavailableReason()
            );
        }
        return publishReady(observation, snapshot.cacheEpoch(), location, capability);
    }

    void invalidate() {
        synchronized (this) {
            cacheEpoch = increment(cacheEpoch);
            clearCached();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            cacheEpoch = increment(cacheEpoch);
            clearCached();
        }
        locator.close();
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
    private synchronized Resolution publishReady(long observation,
                                                 long observedEpoch,
                                                 @Nonnull ClaimPluginLocation location,
                                                 @Nonnull SimpleClaimsDamageGeneration capability) {
        long contractGeneration = increment(reflectedGeneration);
        reflectedGeneration = contractGeneration;
        Resolution candidate = Resolution.ready(
                reflectedGeneration(location, contractGeneration),
                location.pluginVersion(),
                capability
        );
        if (!closed
                && cacheEpoch == observedEpoch
                && observation >= latestPublishedObservation) {
            cachedLocationGeneration = location.generation();
            cachedReady = candidate;
            latestPublishedObservation = observation;
        }
        return candidate;
    }

    private synchronized void clearObserved(long observation) {
        if (!closed && observation >= latestPublishedObservation) {
            clearCached();
            latestPublishedObservation = observation;
        }
    }

    private void clearCached() {
        cachedLocationGeneration = ClaimProviderGeneration.NONE;
        cachedReady = null;
    }

    @Nonnull
    private static Resolution unavailable(@Nonnull ClaimPluginLocation location,
                                          @Nonnull ClaimProviderState state,
                                          String reason) {
        return Resolution.unavailable(
                state,
                location.generation(),
                location.pluginVersion(),
                reason
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

    @Nonnull
    private static String displayVersion(String version) {
        return version == null || version.isBlank() ? "<unknown>" : version;
    }

    @Nonnull
    private static ClaimProviderGeneration reflectedGeneration(@Nonnull ClaimPluginLocation location,
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

    private record Snapshot(long cacheEpoch, Resolution cachedReady) {
    }
}
