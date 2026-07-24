package com.alechilles.alecstamework.damage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the currently live SimpleClaims damage generation for one top-level decision. */
@FunctionalInterface
interface SimpleClaimsDamageCapabilityResolver extends AutoCloseable {
    @Nonnull
    Resolution resolve();

    /** Drops any reflected generation retained by this resolver. */
    default void invalidate() {
    }

    /** Releases optional-plugin references. Fixed test resolvers have nothing to release. */
    @Override
    default void close() {
    }

    record Resolution(@Nonnull SimpleClaimsPluginState state,
                      @Nonnull SimpleClaimsPluginGeneration generation,
                      @Nullable String pluginVersion,
                      @Nullable String reason,
                      @Nullable SimpleClaimsDamageGeneration capability) {
        public Resolution {
            generation = generation == null ? SimpleClaimsPluginGeneration.NONE : generation;
            if (state == SimpleClaimsPluginState.READY && capability == null) {
                throw new IllegalArgumentException("A ready damage generation requires a capability.");
            }
            if (state != SimpleClaimsPluginState.READY && capability != null) {
                throw new IllegalArgumentException("An unavailable damage generation cannot retain a capability.");
            }
        }

        @Nonnull
        static Resolution ready(@Nonnull SimpleClaimsPluginGeneration generation,
                                @Nullable String pluginVersion,
                                @Nonnull SimpleClaimsDamageGeneration capability) {
            return new Resolution(SimpleClaimsPluginState.READY, generation, pluginVersion, null, capability);
        }

        @Nonnull
        static Resolution unavailable(@Nonnull SimpleClaimsPluginState state,
                                      @Nonnull SimpleClaimsPluginGeneration generation,
                                      @Nullable String pluginVersion,
                                      @Nullable String reason) {
            return new Resolution(state, generation, pluginVersion, reason, null);
        }
    }
}
