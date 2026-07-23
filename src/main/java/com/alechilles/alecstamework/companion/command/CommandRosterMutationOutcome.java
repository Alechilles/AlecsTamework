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
                || (before == null && after == null)) {
            throw new IllegalArgumentException(
                    "Valid roster mutation evidence is required"
            );
        }
    }
}
