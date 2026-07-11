package com.alechilles.alecstamework.integration.claims;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Lock-free aggregate metrics for short-lived claim lookup sessions.
 *
 * <p>The metrics retain value-only provider metadata and never retain a plugin bridge or
 * classloader.</p>
 */
public final class ClaimLookupMetrics {
    private final LongAdder sessions = new LongAdder();
    private final LongAdder requests = new LongAdder();
    private final LongAdder providerCalls = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder uniqueChunks = new LongAdder();
    private final LongAdder providerStateChanges = new LongAdder();
    private final AtomicLong totalProviderCallNanos = new AtomicLong();
    private final AtomicLong lastProviderCallNanos = new AtomicLong();
    private final AtomicReference<ProviderSnapshot> provider = new AtomicReference<>();

    public void sessionStarted(@Nonnull ClaimPolicyContext context) {
        Objects.requireNonNull(context, "context");
        sessions.increment();
        ProviderSnapshot next = ProviderSnapshot.from(context);
        ProviderSnapshot previous = provider.getAndSet(next);
        if (previous != null && !previous.sameGenerationAndState(next)) {
            providerStateChanges.increment();
        }
    }

    public void requestRecorded() {
        requests.increment();
    }

    public void cacheHitRecorded() {
        cacheHits.increment();
    }

    public void uniqueChunkRecorded() {
        uniqueChunks.increment();
    }

    public void providerCallRecorded(long elapsedNanos) {
        long safeElapsed = Math.max(0L, elapsedNanos);
        providerCalls.increment();
        totalProviderCallNanos.updateAndGet(current -> saturatedAdd(current, safeElapsed));
        lastProviderCallNanos.set(safeElapsed);
    }

    @Nonnull
    public Snapshot snapshot() {
        return new Snapshot(
                sessions.sum(),
                requests.sum(),
                providerCalls.sum(),
                cacheHits.sum(),
                uniqueChunks.sum(),
                providerStateChanges.sum(),
                totalProviderCallNanos.get(),
                lastProviderCallNanos.get(),
                provider.get()
        );
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    /** Immutable value-only provider context exposed to diagnostics. */
    public record ProviderSnapshot(@Nullable String requestedProvider,
                                   @Nullable String resolvedProvider,
                                   @Nonnull String providerId,
                                   @Nonnull ClaimProviderState state,
                                   @Nullable String reason,
                                   @Nullable String pluginVersion,
                                   @Nonnull ClaimProviderGeneration generation,
                                   long settingsRevision) {
        @Nonnull
        static ProviderSnapshot from(@Nonnull ClaimPolicyContext context) {
            return new ProviderSnapshot(
                    context.requestedProvider() == null ? null : context.requestedProvider().name(),
                    context.resolvedProvider() == null ? null : context.resolvedProvider().name(),
                    context.providerId(),
                    context.state(),
                    context.reason(),
                    context.pluginVersion(),
                    context.providerGeneration(),
                    context.settingsRevision()
            );
        }

        boolean sameGenerationAndState(@Nonnull ProviderSnapshot other) {
            return providerId.equals(other.providerId)
                    && state == other.state
                    && generation.equals(other.generation);
        }
    }

    /** Monotonic aggregate counters. Unique chunks are summed per top-level lookup session. */
    public record Snapshot(long sessions,
                           long requests,
                           long providerCalls,
                           long cacheHits,
                           long uniqueChunks,
                           long providerStateChanges,
                           long totalProviderCallNanos,
                           long lastProviderCallNanos,
                           @Nullable ProviderSnapshot provider) {
    }
}
