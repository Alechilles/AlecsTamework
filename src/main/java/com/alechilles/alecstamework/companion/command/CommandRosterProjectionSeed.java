package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Consistent canonical join used to rebuild one roster action projection. */
public record CommandRosterProjectionSeed(
        @Nonnull CommandRosterMembership membership,
        @Nonnull CompanionIdentity identity,
        @Nullable CompanionAlias currentAlias,
        @Nonnull CompanionLifecycle lifecycle
) {
    public CommandRosterProjectionSeed {
        if (membership == null || identity == null || lifecycle == null
                || !membership.profileId().equals(identity.profileId())
                || !membership.profileId().equals(lifecycle.profileId())
                || currentAlias != null && !membership.profileId().equals(
                currentAlias.profileId()
        )) {
            throw new IllegalArgumentException(
                    "Consistent command roster projection seed is required"
            );
        }
    }
}
