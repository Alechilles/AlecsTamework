package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One uncached PluginManager observation. The plugin object is intentionally short-lived and
 * provider probes must not retain it beyond its reported generation.
 */
public record ClaimPluginLocation(@Nonnull String providerId,
                                  @Nonnull ClaimProviderState state,
                                  @Nullable String pluginVersion,
                                  @Nullable String reason,
                                  @Nonnull ClaimProviderGeneration generation,
                                  @Nullable Object pluginInstance) {
    public ClaimPluginLocation {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID is required.");
        }
        if (state == null || state == ClaimProviderState.INVALID || state == ClaimProviderState.OFF) {
            throw new IllegalArgumentException("A plugin location cannot have state " + state + ".");
        }
        generation = generation == null ? ClaimProviderGeneration.NONE : generation;
        if (state == ClaimProviderState.READY && pluginInstance == null) {
            throw new IllegalArgumentException("A ready plugin location requires a live instance.");
        }
    }

    public boolean hasLivePlugin() {
        return pluginInstance != null;
    }
}
