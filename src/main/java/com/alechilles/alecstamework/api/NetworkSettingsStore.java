package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A server network's shared settings store, supplied by the network's own mod through
 * {@link NetworkSettingsApi#register}. With one installed, the settings an admin publishes on one
 * server apply on every server of the network, and each server may still override single settings.
 *
 * <p>The store holds opaque JSON documents by key. Tamework uses the key {@link #SERVER_KEY} today;
 * keys that start with {@code player/} are reserved. An implementation must accept any key.</p>
 *
 * <p>Tamework calls every method on its own {@code tamework-network-settings} thread, one call at a
 * time, never on a world thread. Calls may block on I/O. A call that throws is retried at the next
 * check; Tamework keeps using the last document it read meanwhile.</p>
 */
public interface NetworkSettingsStore {
    /** The key of the document that holds the network's server settings. */
    String SERVER_KEY = "server";

    /** The stored document for {@code key}, or null when none was saved yet. */
    @Nullable
    NetworkSettingsDocument load(@Nonnull String key) throws Exception;

    /**
     * Stores {@code json} for {@code key} only if the stored revision still equals
     * {@code expectedRevision} ({@code 0} means no document is stored), and gives the new document a
     * higher revision. The compare and the write must be one atomic step, because other servers
     * write too.
     *
     * @return false when the stored revision differs, in which case nothing was written
     */
    boolean save(@Nonnull String key, @Nonnull String json, long expectedRevision) throws Exception;
}
