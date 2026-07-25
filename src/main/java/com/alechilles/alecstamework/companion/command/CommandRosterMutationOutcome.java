package com.alechilles.alecstamework.companion.command;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact before/after evidence for one committed roster membership mutation. */
public record CommandRosterMutationOutcome(
        @Nonnull CommandFamilyKey familyKey,
        long previousRosterRevision,
        long currentRosterRevision,
        @Nullable CommandRosterMembership before,
        @Nullable CommandRosterMembership after
) {
    public CommandRosterMutationOutcome {
        if (familyKey == null || previousRosterRevision < 0
                || currentRosterRevision < previousRosterRevision
                || (before == null && after == null)
                || !familyMatches(familyKey, before)
                || !familyMatches(familyKey, after)
                || before != null && after != null
                && (!before.profileId().equals(after.profileId())
                || !before.slotId().equals(after.slotId()))
                || !validRevisionChange(
                previousRosterRevision,
                currentRosterRevision,
                before,
                after
        )) {
            throw new IllegalArgumentException(
                    "Valid roster mutation evidence is required"
            );
        }
    }

    private static boolean familyMatches(
            CommandFamilyKey familyKey,
            CommandRosterMembership membership
    ) {
        return membership == null
                || familyKey.equals(membership.familyKey());
    }

    private static boolean validRevisionChange(
            long previousRosterRevision,
            long currentRosterRevision,
            CommandRosterMembership before,
            CommandRosterMembership after
    ) {
        if (currentRosterRevision == previousRosterRevision) {
            return before != null && before.equals(after);
        }
        if (previousRosterRevision == Long.MAX_VALUE
                || currentRosterRevision
                != previousRosterRevision + 1) {
            return false;
        }
        if (before == null) {
            return after.membershipRevision() == 1;
        }
        return after == null || after.membershipRevision()
                == before.membershipRevision() + 1;
    }
}

