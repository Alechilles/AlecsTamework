package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import javax.annotation.Nonnull;

/** Complete post-commit timed summon view assembled from canonical authorities. */
public record TimedSummonProjectionView(
        @Nonnull TimedSummonLease lease,
        @Nonnull CommandRosterMembership membership,
        @Nonnull CompanionLifecycle lifecycle
) {
    public TimedSummonProjectionView {
        if (lease == null || membership == null || lifecycle == null
                || !lease.profileId().equals(membership.profileId())
                || !lease.profileId().equals(lifecycle.profileId())) {
            throw new IllegalArgumentException(
                    "Complete timed summon projection join is required"
            );
        }
    }
}
