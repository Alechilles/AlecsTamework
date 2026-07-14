package com.alechilles.alecstamework.persistence.sqlite;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Settles one optional live-identity reservation with its asynchronous SQLite write. */
final class NpcProfileIdentityWriteLease {
    @Nullable
    private final NpcProfileIdentityLifecycle lifecycle;
    @Nullable
    private final NpcProfileIdentityLifecycle.Reservation reservation;

    private NpcProfileIdentityWriteLease(
            @Nullable NpcProfileIdentityLifecycle lifecycle,
            @Nullable NpcProfileIdentityLifecycle.Reservation reservation) {
        this.lifecycle = lifecycle;
        this.reservation = reservation;
    }

    @Nonnull
    static NpcProfileIdentityWriteLease begin(
            @Nullable NpcProfileIdentityLifecycle lifecycle,
            @Nonnull UUID npcUuid) {
        return lifecycle == null
                ? new NpcProfileIdentityWriteLease(null, null)
                : new NpcProfileIdentityWriteLease(lifecycle, lifecycle.reserve(npcUuid));
    }

    @Nullable
    String preferredProfileId() {
        return reservation == null ? null : reservation.profileId();
    }

    void committed(@Nonnull UUID durableCurrentNpcUuid) {
        if (lifecycle != null && reservation != null) {
            lifecycle.committed(reservation, durableCurrentNpcUuid);
        }
    }

    void abortUnlessCommitted(@Nonnull PersistenceWriteQueue.WriteOutcome<?> outcome) {
        if (outcome.status() != PersistenceWriteQueue.WriteStatus.COMMITTED) {
            aborted();
        }
    }

    boolean settleWith(@Nonnull PersistenceWriteQueue.WriteSubmission<?> submission) {
        if (!submission.accepted()) {
            aborted();
        } else {
            submission.completion().thenAccept(this::abortUnlessCommitted);
        }
        return submission.accepted();
    }

    void aborted() {
        if (lifecycle != null && reservation != null) {
            lifecycle.aborted(reservation);
        }
    }
}
