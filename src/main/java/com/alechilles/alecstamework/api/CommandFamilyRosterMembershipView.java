package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable public projection of one canonical roster membership. */
public record CommandFamilyRosterMembershipView(
        @Nonnull UUID ownerUuid,
        @Nonnull String commandFamilyId,
        @Nonnull String profileId,
        @Nonnull String roleId,
        long profileRevision,
        @Nonnull CommandFamilyRosterMemberState state,
        @Nullable String groupId,
        boolean activeForBulkCommands,
        @Nullable Vector3View homePosition,
        long updatedAtMs) {
    public CommandFamilyRosterMembershipView {
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        profileId = requireText(profileId, "profileId");
        roleId = requireText(roleId, "roleId");
        if (profileRevision < 0L) throw new IllegalArgumentException("profileRevision cannot be negative.");
        state = Objects.requireNonNull(state, "state");
        groupId = normalizeOptional(groupId);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
