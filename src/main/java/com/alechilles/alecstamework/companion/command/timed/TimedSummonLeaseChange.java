package com.alechilles.alecstamework.companion.command.timed;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact before/after evidence for one canonical lease mutation. */
public record TimedSummonLeaseChange(
        @Nullable TimedSummonLease before,
        @Nonnull TimedSummonLease after
) {
    public TimedSummonLeaseChange {
        if (after == null
                || before == null && after.leaseRevision() != 1
                || before != null && (!before.profileId().equals(
                after.profileId()
        )
                || before.leaseRevision() == Long.MAX_VALUE
                || after.leaseRevision()
                != before.leaseRevision() + 1
                || before.createdAtMs() != after.createdAtMs())) {
            throw new IllegalArgumentException(
                    "Monotonic timed lease change is required"
            );
        }
    }
}
