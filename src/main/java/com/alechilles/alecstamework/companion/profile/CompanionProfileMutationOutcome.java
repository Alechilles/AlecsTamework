package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;

/** Durable domain outcome of a profile creation or metadata/tool-link update. */
public record CompanionProfileMutationOutcome(
        @Nonnull Status status,
        @Nonnull ProfileId profileId,
        long metadataRevision,
        long updatedAtMs
) {
    public CompanionProfileMutationOutcome {
        if (status == null || profileId == null || metadataRevision < 0) {
            throw new IllegalArgumentException("Complete profile mutation outcome is required");
        }
    }

    public enum Status {
        CREATED,
        UPDATED,
        UNCHANGED,
        REVISION_MISMATCH,
        NOT_FOUND,
        CONFLICT
    }
}
