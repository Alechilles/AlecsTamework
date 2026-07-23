package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable post-commit command action view joined from canonical authorities. */
public record CommandRosterActionView(
        @Nonnull CommandRosterMembership membership,
        @Nonnull String roleId,
        long metadataRevision,
        @Nullable NpcAlias currentAlias,
        @Nonnull CompanionLifecycle lifecycle
) {
    public CommandRosterActionView {
        if (membership == null || roleId == null || roleId.isBlank()
                || metadataRevision < 0 || lifecycle == null
                || !membership.profileId().equals(lifecycle.profileId())
                || !membership.familyKey().ownerId().equals(
                lifecycle.ownerId()
        )) {
            throw new IllegalArgumentException(
                    "Consistent command roster action view is required"
            );
        }
        roleId = roleId.trim();
    }
}
