package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import javax.annotation.Nonnull;

/** Immutable command to retire one exact live companion into one reserved coop slot. */
public record CompanionCoopCaptureRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nonnull CoopSlotKey targetSlot,
        @Nonnull CompanionSnapshot snapshot,
        @Nonnull CoopCaptureSourceEvidence source,
        long requestedAtMs
) {
    public static final SnapshotKind SNAPSHOT_KIND = new SnapshotKind("coop");

    public CompanionCoopCaptureRequest {
        if (profileId == null || expectedLifecycleRevision == null
                || targetSlot == null || snapshot == null || source == null) {
            throw new IllegalArgumentException("Complete coop capture request is required");
        }
        if (!profileId.equals(snapshot.profileId())
                || !SNAPSHOT_KIND.equals(snapshot.kind())
                || !snapshot.current()
                || !snapshot.sourceLifecycleRevision().equals(
                expectedLifecycleRevision.next()
        )) {
            throw new IllegalArgumentException(
                    "Coop snapshot must describe the post-prepare lifecycle fence"
            );
        }
        if (!targetSlot.worldKey().equals(source.sourceWorldKey())) {
            throw new IllegalArgumentException(
                    "Coop capture source and slot must share one world boundary"
            );
        }
    }
}
