package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Self-contained before/after profile change emitted from one canonical transaction.
 */
public record CompanionProfileProjectionChange(
        @Nonnull Source source,
        @Nonnull ProfileId profileId,
        long sourceRevision,
        @Nullable CompanionProfileProjectionState before,
        @Nullable CompanionProfileProjectionState after,
        long changedAtMs
) {
    public CompanionProfileProjectionChange {
        if (source == null || profileId == null || sourceRevision < 0) {
            throw new IllegalArgumentException("Valid profile projection change is required");
        }
        if (before == null && after == null) {
            throw new IllegalArgumentException("Profile projection change needs state");
        }
        requireProfile(profileId, before);
        requireProfile(profileId, after);
    }

    private static void requireProfile(
            ProfileId profileId,
            CompanionProfileProjectionState state
    ) {
        if (state != null && !profileId.equals(state.profileId())) {
            throw new IllegalArgumentException("Profile projection change identity mismatch");
        }
    }

    /** Independent revision domains that may update one public profile. */
    public enum Source {
        METADATA,
        ALIAS,
        LIFECYCLE,
        SNAPSHOT
    }
}
