package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the currently live SimpleClaims damage generation for one top-level decision. */
@FunctionalInterface
interface SimpleClaimsDamageCapabilityResolver {
    @Nonnull
    Resolution resolve();

    record Resolution(@Nonnull ClaimProviderState state,
                      @Nonnull ClaimProviderGeneration generation,
                      @Nullable String pluginVersion,
                      @Nullable String reason,
                      @Nullable SimpleClaimsDamageGeneration capability) {
        public Resolution {
            generation = generation == null ? ClaimProviderGeneration.NONE : generation;
            if (state == ClaimProviderState.READY && capability == null) {
                throw new IllegalArgumentException("A ready damage generation requires a capability.");
            }
            if (state != ClaimProviderState.READY && capability != null) {
                throw new IllegalArgumentException("An unavailable damage generation cannot retain a capability.");
            }
        }

        @Nonnull
        static Resolution ready(@Nonnull ClaimProviderGeneration generation,
                                @Nullable String pluginVersion,
                                @Nonnull SimpleClaimsDamageGeneration capability) {
            return new Resolution(ClaimProviderState.READY, generation, pluginVersion, null, capability);
        }

        @Nonnull
        static Resolution unavailable(@Nonnull ClaimProviderState state,
                                      @Nonnull ClaimProviderGeneration generation,
                                      @Nullable String pluginVersion,
                                      @Nullable String reason) {
            return new Resolution(state, generation, pluginVersion, reason, null);
        }
    }
}
