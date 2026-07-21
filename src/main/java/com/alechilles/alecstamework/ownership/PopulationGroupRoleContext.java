package com.alechilles.alecstamework.ownership;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable old/new canonical role evidence supplied to population-group admission. */
public record PopulationGroupRoleContext(
        @Nullable String oldRoleId,
        @Nullable String newRoleId) {

    public PopulationGroupRoleContext {
        oldRoleId = normalize(oldRoleId);
        newRoleId = normalize(newRoleId);
        if (oldRoleId == null && newRoleId == null) {
            throw new IllegalArgumentException("At least one population-group role is required.");
        }
    }

    @Nonnull
    public static PopulationGroupRoleContext unchanged(@Nonnull String roleId) {
        String role = Objects.requireNonNull(roleId, "roleId");
        return new PopulationGroupRoleContext(role, role);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
