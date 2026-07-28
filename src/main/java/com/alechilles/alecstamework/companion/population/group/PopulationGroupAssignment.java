package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Complete persisted role classification and normalized group membership set. */
public record PopulationGroupAssignment(
        @Nonnull ProfileId profileId,
        @Nullable String roleId,
        @Nonnull List<PopulationGroupMembership> memberships,
        long policyRevision,
        long sourceMetadataRevision,
        @Nonnull LifecycleRevision sourceLifecycleRevision,
        long assignmentRevision,
        long assignedAtMs
) {
    public PopulationGroupAssignment {
        if (profileId == null || sourceLifecycleRevision == null
                || memberships == null || policyRevision < 0
                || sourceMetadataRevision < 0 || assignmentRevision <= 0) {
            throw new IllegalArgumentException(
                    "Complete population group assignment is required"
            );
        }
        roleId = normalize(roleId);
        TreeSet<PopulationGroupMembership> sorted =
                new TreeSet<>(memberships);
        if (sorted.size() != memberships.size()) {
            throw new IllegalArgumentException(
                    "Population group memberships must be unique"
            );
        }
        long distinctGroups = sorted.stream()
                .map(PopulationGroupMembership::groupId)
                .distinct()
                .count();
        if (distinctGroups != sorted.size()) {
            throw new IllegalArgumentException(
                    "One profile cannot assign two scopes to one group"
            );
        }
        memberships = List.copyOf(sorted);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

