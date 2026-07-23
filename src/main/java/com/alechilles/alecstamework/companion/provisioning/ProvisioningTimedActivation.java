package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import javax.annotation.Nonnull;

/** Optional initial timed-session evidence attached to live activation. */
public record ProvisioningTimedActivation(
        @Nonnull CommandFamilyKey familyKey,
        @Nonnull CommandRosterSlotId slotId,
        long expectedMembershipRevision,
        @Nonnull TimedSummonLease lease
) {
    public ProvisioningTimedActivation {
        if (familyKey == null || slotId == null || lease == null
                || expectedMembershipRevision <= 0) {
            throw new IllegalArgumentException(
                    "Complete provisioning timed activation is required"
            );
        }
    }
}
