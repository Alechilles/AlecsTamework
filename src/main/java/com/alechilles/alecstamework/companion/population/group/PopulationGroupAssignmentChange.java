package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Self-contained before/after assignment projection evidence. */
public record PopulationGroupAssignmentChange(
        @Nonnull ProfileId profileId,
        @Nullable PopulationGroupAssignment before,
        @Nonnull PopulationGroupAssignment after
) {
    public PopulationGroupAssignmentChange {
        if (profileId == null || after == null
                || !profileId.equals(after.profileId())
                || (before != null
                && !profileId.equals(before.profileId()))) {
            throw new IllegalArgumentException(
                    "Consistent group assignment change is required"
            );
        }
    }
}

