package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import javax.annotation.Nonnull;

/** Self-contained durable capture result published through the shared outbox. */
public record CompanionCaptureOutcome(@Nonnull ProfileId profileId,
                                      @Nonnull SnapshotId snapshotId,
                                      @Nonnull LifecycleRevision lifecycleRevision,
                                      @Nonnull String sourceReceiptKey,
                                      long capturedAtMs) {
    public CompanionCaptureOutcome {
        if (profileId == null || snapshotId == null || lifecycleRevision == null
                || sourceReceiptKey == null || sourceReceiptKey.isBlank()) {
            throw new IllegalArgumentException("Complete companion capture outcome is required");
        }
        sourceReceiptKey = sourceReceiptKey.trim();
    }
}
