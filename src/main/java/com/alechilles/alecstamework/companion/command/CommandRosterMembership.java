package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical command-family slot and command-only preferences for one profile. */
public record CommandRosterMembership(
        @Nonnull CommandRosterSlotId slotId,
        @Nonnull CommandFamilyKey familyKey,
        @Nonnull ProfileId profileId,
        long membershipRevision,
        @Nullable String groupId,
        boolean activeForBulkCommands,
        @Nullable CommandRosterHome home,
        long createdAtMs,
        long updatedAtMs
) implements Comparable<CommandRosterMembership> {
    public CommandRosterMembership {
        if (slotId == null || familyKey == null || profileId == null
                || membershipRevision <= 0) {
            throw new IllegalArgumentException(
                    "Complete command roster membership is required"
            );
        }
        groupId = normalize(groupId);
    }

    @Override
    public int compareTo(CommandRosterMembership other) {
        if (other == null) {
            throw new NullPointerException(
                    "Other command roster membership is required"
            );
        }
        return profileId.value().compareTo(other.profileId().value());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
