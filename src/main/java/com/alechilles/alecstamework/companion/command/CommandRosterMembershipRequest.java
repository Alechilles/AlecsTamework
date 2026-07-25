package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact canonical evidence and desired command preferences for one slot mutation. */
public record CommandRosterMembershipRequest(
        @Nonnull Action action,
        @Nonnull ProfileId profileId,
        @Nonnull CommandFamilyKey familyKey,
        @Nonnull CommandRosterSlotId slotId,
        long expectedRosterRevision,
        @Nullable Long expectedMembershipRevision,
        long expectedMetadataRevision,
        @Nonnull String expectedRoleId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nullable String expectedOwnerWorldKey,
        @Nullable String groupId,
        boolean activeForBulkCommands,
        @Nullable CommandRosterHome home,
        long requestedAtMs
) {
    public CommandRosterMembershipRequest {
        if (action == null || profileId == null || familyKey == null
                || slotId == null || expectedRosterRevision < 0
                || expectedMetadataRevision < 0
                || expectedRoleId == null || expectedRoleId.isBlank()
                || expectedLifecycleRevision == null) {
            throw new IllegalArgumentException(
                    "Complete command roster mutation request is required"
            );
        }
        if (expectedMembershipRevision != null
                && expectedMembershipRevision <= 0
                || action == Action.REMOVE
                && expectedMembershipRevision == null) {
            throw new IllegalArgumentException(
                    "Valid expected membership revision is required"
            );
        }
        expectedRoleId = expectedRoleId.trim();
        expectedOwnerWorldKey = normalize(expectedOwnerWorldKey);
        groupId = normalize(groupId);
    }

    public enum Action {
        UPSERT,
        REMOVE
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}

