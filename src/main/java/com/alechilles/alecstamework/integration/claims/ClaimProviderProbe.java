package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;

/**
 * Lazy provider probe. Implementations may cache reflected contracts only for their current
 * plugin-instance/classloader generation and must release those references on invalidation.
 */
public interface ClaimProviderProbe extends AutoCloseable {
    @Nonnull
    ClaimIntegrationProvider provider();

    @Nonnull
    ClaimProviderProbeResult probe();

    default void invalidate() {
    }

    @Override
    default void close() {
        invalidate();
    }
}
