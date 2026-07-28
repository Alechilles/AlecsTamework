package com.alechilles.alecstamework.companion.dormant;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import javax.annotation.Nonnull;

/** Immutable command to replace one positively observed live companion with a dormant snapshot. */
public record CompanionDormantTransitionRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nonnull CompanionSnapshot snapshot,
        @Nonnull DormantSourceEvidence source,
        long requestedAtMs
) {
    public CompanionDormantTransitionRequest {
        if (profileId == null || expectedLifecycleRevision == null
                || snapshot == null || source == null) {
            throw new IllegalArgumentException("Complete dormant transition request is required");
        }
        if (!profileId.equals(snapshot.profileId())
                || !source.kind().snapshotKind().equals(snapshot.kind())
                || !snapshot.current()
                || !snapshot.sourceLifecycleRevision().equals(expectedLifecycleRevision)) {
            throw new IllegalArgumentException(
                    "Dormant snapshot must describe the exact pre-transition lifecycle"
            );
        }
    }

    /** Returns the sole lifecycle state implied by the positive source evidence. */
    @Nonnull
    public LifecycleState targetState() {
        return source.kind().targetState();
    }
}
