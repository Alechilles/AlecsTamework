package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Server network support: install the network's {@link PortableCompanionStore}. Advertised by
 * {@link TameworkApiCapability#PORTABLE_COMPANION_STORE}. Without a store every server keeps its
 * companions to itself, as before.
 */
public interface PortableCompanionsApi {
    /** Whether this facade can accept a store. */
    boolean available();

    /** Whether a store is installed now. */
    boolean active();

    /**
     * Installs the sole store and returns an idempotent handle that removes it. Once installed, every
     * owned companion this server holds is written to the store in the background, and each owner's
     * entries are read when they join.
     *
     * @throws IllegalStateException if another store is installed or this facade is unavailable
     */
    @Nonnull
    AutoCloseable register(@Nonnull PortableCompanionStore store);

    /** Returns a shared unavailable facade for older and degraded API compositions. */
    @Nonnull
    static PortableCompanionsApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Shared fail-closed implementation. */
    final class UnavailableHolder {
        private static final PortableCompanionsApi INSTANCE = new PortableCompanionsApi() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public boolean active() {
                return false;
            }

            @Override
            public AutoCloseable register(PortableCompanionStore store) {
                Objects.requireNonNull(store, "store");
                throw new IllegalStateException("portable companion store is not available");
            }
        };

        private UnavailableHolder() {
        }
    }
}
