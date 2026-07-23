package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import javax.annotation.Nonnull;

/** Optional command-family timed-session evidence attached to live activation. */
public record TimedSummonActivation(
        @Nonnull CommandFamilyKey familyKey,
        @Nonnull CommandRosterSlotId slotId,
        long expectedMembershipRevision,
        @Nonnull TimedSummonLease lease
) {
    public TimedSummonActivation {
        if (familyKey == null || slotId == null || lease == null
                || expectedMembershipRevision <= 0) {
            throw new IllegalArgumentException(
                    "Complete timed summon activation is required"
            );
        }
    }
}
