package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;

/**
 * Locates the current live plugin object and lifecycle generation for a provider probe.
 */
public interface ClaimPluginLocator extends AutoCloseable {
    @Nonnull
    ClaimPluginLocation locate();

    @Override
    default void close() {
    }
}
