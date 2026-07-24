package com.alechilles.alecstamework.damage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One uncached observation of the optional SimpleClaims plugin. */
record SimpleClaimsPluginLocation(@Nonnull SimpleClaimsPluginState state,
                                  @Nullable String pluginVersion,
                                  @Nullable String reason,
                                  @Nonnull SimpleClaimsPluginGeneration generation,
                                  @Nullable Object pluginInstance) {
    SimpleClaimsPluginLocation {
        if (state == null) {
            throw new IllegalArgumentException("Plugin state is required.");
        }
        generation = generation == null ? SimpleClaimsPluginGeneration.NONE : generation;
        if (state == SimpleClaimsPluginState.READY && pluginInstance == null) {
            throw new IllegalArgumentException("A ready plugin location requires a live instance.");
        }
    }
}
