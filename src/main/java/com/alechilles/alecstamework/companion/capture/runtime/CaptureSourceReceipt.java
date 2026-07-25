package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import javax.annotation.Nonnull;

/** Exact persistent player-side receipt written before resolved source spending. */
record CaptureSourceReceipt(
        @Nonnull String receiptKey,
        @Nonnull ProfileId profileId,
        @Nonnull NpcAlias targetAlias,
        int slot,
        @Nonnull String sourceItemId,
        int quantity,
        @Nonnull Sha256Hash sourceFingerprint
) {
    CaptureSourceReceipt {
        if (receiptKey == null || receiptKey.isBlank()
                || profileId == null || targetAlias == null
                || slot < 0 || sourceItemId == null
                || sourceItemId.isBlank() || quantity <= 0
                || sourceFingerprint == null) {
            throw new IllegalArgumentException(
                    "Complete capture source receipt is required"
            );
        }
        receiptKey = receiptKey.trim();
        sourceItemId = sourceItemId.trim();
    }

    static CaptureSourceReceipt exact(CompanionCaptureRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Capture request is required"
            );
        }
        return new CaptureSourceReceipt(
                request.source().receiptKey(),
                request.profileId(),
                request.targetAlias(),
                request.source().slot(),
                request.source().sourceItemId(),
                request.source().quantity(),
                request.source().beforeFingerprint()
        );
    }
}
