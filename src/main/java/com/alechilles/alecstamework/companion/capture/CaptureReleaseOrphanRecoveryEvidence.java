package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact initial profile authority reconstructed from one item-only public capture. */
public record CaptureReleaseOrphanRecoveryEvidence(
        @Nonnull CompanionIdentity initialIdentity,
        @Nullable OwnerId initialOwner
) {
    public CaptureReleaseOrphanRecoveryEvidence {
        if (initialIdentity == null
                || initialIdentity.metadataRevision() != 0L
                || initialIdentity.roleId() == null) {
            throw new IllegalArgumentException(
                    "Complete initial item-only capture identity is required"
            );
        }
    }
}
