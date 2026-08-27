package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Optional synchronous registration and resolution surface for husbandry outcomes. */
public interface HusbandryOutcomeApi {
    /** Returns whether this facade can accept and resolve a live provider. */
    boolean available();

    /**
     * Registers the sole active provider and returns an idempotent unregister handle.
     *
     * @throws IllegalStateException if another provider is already active or this facade is closed
     */
    @Nonnull
    AutoCloseable register(@Nonnull HusbandryOutcomeProvider provider);

    /** Resolves modifiers, returning identity values when the provider is unavailable or fails. */
    @Nonnull
    HusbandryOutcomeModifiers resolve(@Nonnull HusbandryOutcomeContext context);

    /** Returns a shared unavailable facade for legacy and degraded API compositions. */
    @Nonnull
    static HusbandryOutcomeApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Shared fail-closed implementation for older and degraded API compositions. */
    final class UnavailableHolder {
        private static final HusbandryOutcomeApi INSTANCE = new HusbandryOutcomeApi() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public AutoCloseable register(HusbandryOutcomeProvider provider) {
                Objects.requireNonNull(provider, "provider");
                return () -> {
                    // No provider is retained by the unavailable facade.
                };
            }

            @Override
            public HusbandryOutcomeModifiers resolve(HusbandryOutcomeContext context) {
                return HusbandryOutcomeModifiers.identity();
            }
        };

        private UnavailableHolder() {
        }
    }
}
