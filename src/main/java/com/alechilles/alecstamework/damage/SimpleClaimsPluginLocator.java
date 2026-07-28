package com.alechilles.alecstamework.damage;

import javax.annotation.Nonnull;

/** Locates the current live SimpleClaims plugin generation. */
interface SimpleClaimsPluginLocator extends AutoCloseable {
    @Nonnull
    SimpleClaimsPluginLocation locate();

    @Override
    default void close() {
    }
}
