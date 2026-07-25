package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Revision-fenced, idempotent mutation request for one canonical roster membership. */
public record CommandFamilyRosterMutationRequest(
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nullable UUID correlationId,
        @Nonnull UUID ownerUuid,
        @Nonnull String commandFamilyId,
        @Nonnull String profileId,
        @Nullable String requiredCommandConfigId,
        @Nullable String accessItemId,
        @Nonnull CommandFamilyRosterMemberState state,
        @Nullable String groupId,
        boolean activeForBulkCommands,
        @Nullable Vector3View homePosition,
        long expectedRevision,
        long expectedProfileRevision) {
    public CommandFamilyRosterMutationRequest {
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        profileId = requireText(profileId, "profileId");
        requiredCommandConfigId = normalizeOptional(requiredCommandConfigId);
        accessItemId = normalizeOptional(accessItemId);
        state = Objects.requireNonNull(state, "state");
        groupId = normalizeOptional(groupId);
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision cannot be negative.");
        }
        if (expectedProfileRevision < 0L) {
            throw new IllegalArgumentException("expectedProfileRevision cannot be negative.");
        }
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
