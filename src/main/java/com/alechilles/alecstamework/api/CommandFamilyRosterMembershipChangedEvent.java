package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Post-commit notification for a canonical owner/family/profile roster change. */
public record CommandFamilyRosterMembershipChangedEvent(
        @Nonnull UUID operationId,
        @Nonnull UUID ownerUuid,
        @Nonnull String commandFamilyId,
        @Nonnull String profileId,
        @Nullable CommandFamilyRosterMembershipView previousMembership,
        @Nullable CommandFamilyRosterMembershipView currentMembership,
        long previousRevision,
        long currentRevision,
        long changedAtMs,
        long emittedAtMs) implements TameworkEvent {
    public CommandFamilyRosterMembershipChangedEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        profileId = requireText(profileId, "profileId");
        if (previousRevision < 0L || currentRevision < previousRevision) {
            throw new IllegalArgumentException("Roster revisions are invalid.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
