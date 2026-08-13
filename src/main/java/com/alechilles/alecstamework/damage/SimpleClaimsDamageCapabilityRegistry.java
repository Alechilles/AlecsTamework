package com.alechilles.alecstamework.damage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Positive-generation cache for SimpleClaims damage, identity, and claim access.
 *
 * <p>Every resolve observes PluginManager first. Absent, disabled, failed, and incompatible states
 * are never sticky. Locator and reflection work occur outside the registry monitor, and an older
 * in-flight observation cannot replace a newer published generation.</p>
 */
final class SimpleClaimsDamageCapabilityRegistry
        implements SimpleClaimsDamageCapabilityResolver, AutoCloseable {
    private final SimpleClaimsPluginLocator locator;
    private final Function<Object, SimpleClaimsDamageGeneration> generationFactory;
    private final AtomicLong observationSequence = new AtomicLong();
    private SimpleClaimsPluginGeneration cachedLocationGeneration = SimpleClaimsPluginGeneration.NONE;
    private Resolution cachedReady;
    private long reflectedGeneration;
    private long cacheEpoch;
    private long latestPublishedObservation;
    private boolean closed;

    SimpleClaimsDamageCapabilityRegistry() {
        this(
                new HytaleSimpleClaimsPluginLocator(),
                SimpleClaimsDamageGeneration::reflect
        );
    }

    SimpleClaimsDamageCapabilityRegistry(@Nonnull SimpleClaimsPluginLocator locator,
                                         @Nonnull Function<Object, SimpleClaimsDamageGeneration> generationFactory) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.generationFactory = Objects.requireNonNull(generationFactory, "generationFactory");
    }

    @Nonnull
    @Override
    public Resolution resolve() {
        if (isClosed()) {
            return Resolution.unavailable(
                    SimpleClaimsPluginState.ERROR,
                    SimpleClaimsPluginGeneration.NONE,
                    null,
                    "SimpleClaims damage capability registry is shut down."
            );
        }
        long observation = observationSequence.incrementAndGet();
        SimpleClaimsPluginLocation location = locate();
        if (location.state() != SimpleClaimsPluginState.READY || location.pluginInstance() == null) {
            clearObserved(observation);
            return unavailable(location, location.state(), location.reason());
        }
        if (!supported(location.pluginVersion())) {
            clearObserved(observation);
            return unavailable(
                    location,
                    SimpleClaimsPluginState.INCOMPATIBLE,
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
                    SimpleClaimsPluginState.INCOMPATIBLE,
                    "SimpleClaims damage reflection failed: " + message(throwable)
            );
        }
        if (capability == null || !capability.usable()) {
            clearObserved(observation);
            return unavailable(
                    location,
                    SimpleClaimsPluginState.INCOMPATIBLE,
                    capability == null
                            ? "SimpleClaims damage reflection returned no capability."
                            : capability.unavailableReason()
            );
        }
        return publishReady(observation, snapshot.cacheEpoch(), location, capability);
    }

    @Override
    public void invalidate() {
        synchronized (this) {
            if (closed) {
                return;
            }
            cacheEpoch = increment(cacheEpoch);
            clearCached();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            cacheEpoch = increment(cacheEpoch);
            clearCached();
        }
        locator.close();
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    @Nonnull
    private SimpleClaimsPluginLocation locate() {
        try {
            return locator.locate();
        } catch (Throwable throwable) {
            return new SimpleClaimsPluginLocation(
                    SimpleClaimsPluginState.ERROR,
                    null,
                    "SimpleClaims plugin lookup failed: " + message(throwable),
                    SimpleClaimsPluginGeneration.NONE,
                    null
            );
        }
    }

    @Nonnull
    private synchronized Snapshot snapshot(long observation,
                                           @Nonnull SimpleClaimsPluginGeneration locationGeneration) {
        if (closed) {
            return new Snapshot(cacheEpoch, null);
        }
        Resolution ready = cachedReady;
        if (ready != null && cachedLocationGeneration.equals(locationGeneration)) {
            latestPublishedObservation = Math.max(latestPublishedObservation, observation);
            return new Snapshot(cacheEpoch, ready);
        }
        return new Snapshot(cacheEpoch, null);
    }

    @Nonnull
    private synchronized Resolution publishReady(long observation,
                                                 long observedEpoch,
                                                 @Nonnull SimpleClaimsPluginLocation location,
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
        cachedLocationGeneration = SimpleClaimsPluginGeneration.NONE;
        cachedReady = null;
    }

    @Nonnull
    private static Resolution unavailable(@Nonnull SimpleClaimsPluginLocation location,
                                          @Nonnull SimpleClaimsPluginState state,
                                          String reason) {
        return Resolution.unavailable(
                state,
                location.generation(),
                location.pluginVersion(),
                reason
        );
    }

    private static boolean supported(String version) {
        return SimpleClaimsPluginVersion.isSupported(version);
    }

    @Nonnull
    private static String displayVersion(String version) {
        return version == null || version.isBlank() ? "<unknown>" : version;
    }

    @Nonnull
    private static SimpleClaimsPluginGeneration reflectedGeneration(
            @Nonnull SimpleClaimsPluginLocation location,
            long reflectedGeneration) {
        return new SimpleClaimsPluginGeneration(
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
