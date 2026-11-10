package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Server network support for settings: install the network's {@link NetworkSettingsStore}.
 * Advertised by {@link TameworkApiCapability#NETWORK_SETTINGS_STORE}. Without a store every server
 * keeps its own settings, as before.
 */
public interface NetworkSettingsApi {
    /** Whether this facade can accept a store. */
    boolean available();

    /** Whether a store is installed now. */
    boolean active();

    /**
     * Installs the sole store and returns an idempotent handle that removes it. Once installed, the
     * network's settings are read in the background, then again every minute and after each
     * {@link #notifyChanged}.
     *
     * @throws IllegalStateException if another store is installed or this facade is unavailable
     */
    @Nonnull
    AutoCloseable register(@Nonnull NetworkSettingsStore store);

    /**
     * Tells Tamework that another server saved {@code key}, so it reads the document now instead of
     * at the next check. Safe from any thread; returns at once. Does nothing without a store.
     */
    void notifyChanged(@Nonnull String key);

    /** Returns a shared unavailable facade for older and degraded API compositions. */
    @Nonnull
    static NetworkSettingsApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Shared fail-closed implementation. */
    final class UnavailableHolder {
        private static final NetworkSettingsApi INSTANCE = new NetworkSettingsApi() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public boolean active() {
                return false;
            }

            @Override
            public AutoCloseable register(NetworkSettingsStore store) {
                Objects.requireNonNull(store, "store");
                throw new IllegalStateException("network settings store is not available");
            }

            @Override
            public void notifyChanged(String key) {
                Objects.requireNonNull(key, "key");
            }
        };

        private UnavailableHolder() {
        }
    }
}
