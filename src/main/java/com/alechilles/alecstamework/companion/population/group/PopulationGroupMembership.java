package com.alechilles.alecstamework.companion.population.group;

import javax.annotation.Nonnull;

/** One normalized group and scope beneath a profile classification. */
public record PopulationGroupMembership(
        @Nonnull String groupId,
        @Nonnull PopulationGroupScope scope
) implements Comparable<PopulationGroupMembership> {
    public PopulationGroupMembership {
        if (groupId == null || groupId.isBlank() || scope == null) {
            throw new IllegalArgumentException(
                    "Population group membership and scope are required"
            );
        }
        groupId = groupId.trim();
    }

    @Override
    public int compareTo(PopulationGroupMembership other) {
        if (other == null) {
            throw new NullPointerException(
                    "Other population group membership is required"
            );
        }
        int group = groupId.compareTo(other.groupId);
        return group != 0
                ? group
                : scope.compareTo(other.scope);
    }
}
