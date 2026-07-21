package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/** Immutable resolved definition of one logical companion population group. */
public record PopulationGroupDefinitionView(@Nonnull String configId,
                                            long configRevision,
                                            @Nonnull String groupId,
                                            @Nonnull Set<String> roleIds,
                                            long maxOwnedPerOwner,
                                            long maxActivePerOwner,
                                            @Nonnull PopulationGroupScope scope) {
    public PopulationGroupDefinitionView {
        configId = requireText(configId, "configId");
        groupId = requireText(groupId, "groupId");
        roleIds = copyIds(roleIds, false);
        scope = Objects.requireNonNull(scope, "scope");
        if (configRevision < 0L || maxOwnedPerOwner < 0L || maxActivePerOwner < 0L) {
            throw new IllegalArgumentException("Population-group revisions and limits cannot be negative.");
        }
    }

    private static Set<String> copyIds(Set<String> values, boolean allowEmpty) {
        if (values == null || values.isEmpty()) {
            if (allowEmpty) return Set.of();
            throw new IllegalArgumentException("roleIds cannot be empty.");
        }
        Set<String> copied = values.stream().map(value -> requireText(value, "roleId"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!allowEmpty && copied.isEmpty()) throw new IllegalArgumentException("roleIds cannot be empty.");
        return copied;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
