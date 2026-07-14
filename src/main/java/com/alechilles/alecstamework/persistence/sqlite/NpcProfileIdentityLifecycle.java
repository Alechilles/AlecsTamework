package com.alechilles.alecstamework.persistence.sqlite;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Reserves a live identity before an asynchronous profile write and settles it with that write.
 *
 * <p>This keeps world-thread identity observers aligned with profile aliases created after the
 * startup alias snapshot without coupling SQLite repositories to the ownership implementation.</p>
 */
public interface NpcProfileIdentityLifecycle {
    @Nonnull
    Reservation reserve(@Nonnull UUID npcUuid);

    void committed(@Nonnull Reservation reservation, @Nonnull UUID durableCurrentNpcUuid);

    void aborted(@Nonnull Reservation reservation);

    record Reservation(@Nonnull String profileId,
                       @Nonnull UUID npcUuid,
                       boolean provisional) {
        public Reservation {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId is required");
            }
            if (npcUuid == null) {
                throw new IllegalArgumentException("npcUuid is required");
            }
            profileId = profileId.trim();
        }
    }
}
