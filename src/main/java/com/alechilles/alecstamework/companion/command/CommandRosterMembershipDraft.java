package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Desired command slot/preferences before the store assigns its next revision. */
public record CommandRosterMembershipDraft(
        @Nonnull CommandRosterSlotId slotId,
        @Nonnull CommandFamilyKey familyKey,
        @Nonnull ProfileId profileId,
        @Nullable String groupId,
        boolean activeForBulkCommands,
        @Nullable CommandRosterHome home,
        long changedAtMs
) {
    public CommandRosterMembershipDraft {
        if (slotId == null || familyKey == null || profileId == null) {
            throw new IllegalArgumentException(
                    "Complete command roster membership draft is required"
            );
        }
        groupId = groupId == null || groupId.isBlank()
                ? null
                : groupId.trim();
    }
}

