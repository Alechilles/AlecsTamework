package com.alechilles.alecstamework.companion.command;

import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;

/** Immutable canonical command-family roster with sorted unique membership. */
public record CommandRoster(
        @Nonnull CommandFamilyKey familyKey,
        long rosterRevision,
        @Nonnull List<CommandRosterMembership> memberships,
        long createdAtMs,
        long updatedAtMs
) {
    public CommandRoster {
        if (familyKey == null || rosterRevision < 0
                || memberships == null) {
            throw new IllegalArgumentException(
                    "Complete command roster is required"
            );
        }
        TreeSet<CommandRosterMembership> sorted =
                new TreeSet<>(memberships);
        if (sorted.size() != memberships.size()
                || sorted.stream().anyMatch(membership ->
                !familyKey.equals(membership.familyKey()))) {
            throw new IllegalArgumentException(
                    "Roster membership must be unique and family-consistent"
            );
        }
        memberships = List.copyOf(sorted);
    }
}
