package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Optional synchronous presentation contribution for an item containing a captured NPC. */
public interface CapturedItemDisplayApi {
    /** Returns whether this facade can accept and resolve a live provider. */
    boolean available();

    /**
     * Registers the sole active provider and returns an idempotent unregister handle.
     *
     * @throws IllegalStateException if another provider is already active or this facade is closed
     */
    @Nonnull
    AutoCloseable register(@Nonnull CapturedItemDisplayProvider provider);

    /** Resolves an optional contribution, returning none when the provider is unavailable or fails. */
    @Nonnull
    CapturedItemDisplayContribution resolve(@Nonnull CapturedItemDisplayContext context);

    /** Returns a shared unavailable facade for legacy and degraded API compositions. */
    @Nonnull
    static CapturedItemDisplayApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Shared no-op implementation for older and degraded API compositions. */
    final class UnavailableHolder {
        private static final CapturedItemDisplayApi INSTANCE = new CapturedItemDisplayApi() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public AutoCloseable register(CapturedItemDisplayProvider provider) {
                Objects.requireNonNull(provider, "provider");
                return () -> {
                    // No provider is retained by the unavailable facade.
                };
            }

            @Override
            public CapturedItemDisplayContribution resolve(CapturedItemDisplayContext context) {
                return CapturedItemDisplayContribution.none();
            }
        };

        private UnavailableHolder() {
        }
    }
}
