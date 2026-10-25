package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Optional synchronous registration and resolution surface for husbandry reward tables. */
public interface HusbandryRewardTableApi {
    /** Returns whether this facade can accept and resolve a live provider. */
    boolean available();

    /**
     * Registers the sole active provider and returns an idempotent unregister handle.
     *
     * @throws IllegalStateException if another provider is already active or this facade is closed
     */
    @Nonnull
    AutoCloseable register(@Nonnull HusbandryRewardTableProvider provider);

    /**
     * Resolves the provider's table, or {@code null} when no provider is active,
     * the provider returns {@code null}, or the provider throws.
     */
    @Nullable
    HusbandryRewardTable resolve(@Nonnull HusbandryOutcomeContext context);

    /** Returns a shared unavailable facade for legacy and degraded API compositions. */
    @Nonnull
    static HusbandryRewardTableApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Shared fail-closed implementation for older and degraded API compositions. */
    final class UnavailableHolder {
        private static final HusbandryRewardTableApi INSTANCE = new HusbandryRewardTableApi() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public AutoCloseable register(HusbandryRewardTableProvider provider) {
                Objects.requireNonNull(provider, "provider");
                return () -> {
                    // No provider is retained by the unavailable facade.
                };
            }

            @Override
            public HusbandryRewardTable resolve(HusbandryOutcomeContext context) {
                return null;
            }
        };

        private UnavailableHolder() {
        }
    }
}
