package com.alechilles.alecstamework.ownership;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Final owner/claim index and durability result after a live mutation succeeds. */
public record CompanionPopulationCommitResult(
        boolean committed,
        @Nonnull String reason,
        boolean claimCommitted,
        @Nullable OwnerPopulationCommitResult ownerCommit
) {
}
