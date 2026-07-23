package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Optional command-family timed-session evidence attached to live activation. */
public record TimedSummonActivation(
        @Nonnull CommandFamilyKey familyKey,
        @Nonnull CommandRosterSlotId slotId,
        long expectedMembershipRevision,
        @Nullable TimedSummonLease expectedPreviousLease,
        @Nonnull TimedSummonLease lease
) {
    public TimedSummonActivation {
        if (familyKey == null || slotId == null || lease == null
                || expectedMembershipRevision <= 0) {
            throw new IllegalArgumentException(
                    "Complete timed summon activation is required"
            );
        }
        if (expectedPreviousLease == null) {
            if (lease.leaseRevision() != 1) {
                throw new IllegalArgumentException(
                        "Initial timed activation requires lease revision one"
                );
            }
        } else {
            new TimedSummonLeaseChange(expectedPreviousLease, lease);
        }
    }

    /** Creates an initial activation when no canonical lease exists. */
    public TimedSummonActivation(
            CommandFamilyKey familyKey,
            CommandRosterSlotId slotId,
            long expectedMembershipRevision,
            TimedSummonLease lease
    ) {
        this(
                familyKey,
                slotId,
                expectedMembershipRevision,
                null,
                lease
        );
    }
}
