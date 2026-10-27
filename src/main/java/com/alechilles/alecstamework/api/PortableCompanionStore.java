package com.alechilles.alecstamework.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A server network's shared companion store, supplied by the network's own mod through
 * {@link PortableCompanionsApi#register}. With one installed, companions that follow their owner
 * (see {@link PortableCompanionEntry}) are written to it and read back on whichever server the
 * owner joins, and owner limits count the companions other servers hold.
 *
 * <p>Tamework calls every method on its own {@code tamework-portable-store} thread, one call at a
 * time, never on a world thread. Calls may block on I/O. A call that throws is retried later;
 * Tamework keeps working from its local files meanwhile.</p>
 */
public interface PortableCompanionStore {
    /**
     * This server's id within the network: unique, and the same across restarts. It names the server
     * in {@link PortableCompanionEntry#holder()}.
     */
    @Nonnull
    String holderId();

    /**
     * Every stored entry whose owner is {@code ownerUuid}, plus the entries of {@code profileIds}
     * whoever owns them now. Either may be empty; {@code ownerUuid} may be null to read by id only.
     */
    @Nonnull
    List<PortableCompanionEntry> load(@Nullable UUID ownerUuid, @Nonnull Collection<UUID> profileIds)
            throws Exception;

    /**
     * Stores each entry that {@link PortableCompanionEntry#replaces replaces} the entry stored for its
     * profile (it is newer, or the stored one has the same {@code holder}), or has none, replacing it
     * whole; others are skipped without an error. Each entry must be compared and written in one
     * atomic step, because other servers write too.
     */
    void save(@Nonnull List<PortableCompanionEntry> entries) throws Exception;
}
