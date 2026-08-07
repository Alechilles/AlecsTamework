package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import javax.annotation.Nonnull;

/** Exact already-migrated capture-v1 history admitted for one legacy item release. */
public record CaptureReleaseLegacyRecoveryEvidence(
        @Nonnull CompanionSnapshot historicalSnapshot,
        @Nonnull ReconciliationGeneration reconciliationGeneration,
        long sourceAliasGeneration,
        long sourceAliasMappedAtMs
) {
    public CaptureReleaseLegacyRecoveryEvidence {
        if (historicalSnapshot == null || reconciliationGeneration == null
                || historicalSnapshot.current()
                || !CompanionCaptureRequest.SNAPSHOT_KIND.equals(
                historicalSnapshot.kind()
        )
                || historicalSnapshot.payloadVersion() != 1
                || sourceAliasGeneration < 0L) {
            throw new IllegalArgumentException(
                    "Exact non-current capture-v1 recovery evidence is required"
            );
        }
    }
}
