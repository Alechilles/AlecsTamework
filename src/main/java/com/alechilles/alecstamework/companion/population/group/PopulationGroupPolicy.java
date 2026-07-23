package com.alechilles.alecstamework.companion.population.group;

import javax.annotation.Nonnull;

/** Immutable winning group policy accepted for one classification revision. */
public record PopulationGroupPolicy(
        @Nonnull String groupId,
        @Nonnull PopulationGroupScope scope,
        int maxOwnedPerOwner,
        int maxActivePerOwner,
        long policyRevision
) implements Comparable<PopulationGroupPolicy> {
    public PopulationGroupPolicy {
        groupId = requireText(groupId, "Population group ID");
        if (scope == null || maxOwnedPerOwner < 0
                || maxActivePerOwner < 0 || policyRevision < 0) {
            throw new IllegalArgumentException(
                    "Valid population group policy is required"
            );
        }
    }

    @Override
    public int compareTo(PopulationGroupPolicy other) {
        if (other == null) {
            throw new NullPointerException("Other group policy is required");
        }
        return groupId.compareTo(other.groupId);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
