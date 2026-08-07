package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import javax.annotation.Nonnull;

/** Exact older canonical capture authority superseded by a newer same-profile item. */
public record CaptureReleaseModernRecoveryEvidence(
        @Nonnull CompanionSnapshot supersededSnapshot,
        @Nonnull NpcAlias canonicalSourceAlias,
        @Nonnull ReconciliationGeneration reconciliationGeneration,
        long canonicalAliasGeneration,
        long canonicalAliasMappedAtMs
) {
    public CaptureReleaseModernRecoveryEvidence {
        if (supersededSnapshot == null || canonicalSourceAlias == null
                || reconciliationGeneration == null
                || !supersededSnapshot.current()
                || !CompanionCaptureRequest.SNAPSHOT_KIND.equals(
                supersededSnapshot.kind()
        )
                || (supersededSnapshot.payloadVersion() != 1
                && supersededSnapshot.payloadVersion()
                != CompanionCaptureRequest.SNAPSHOT_VERSION)
                || canonicalAliasGeneration < 0L) {
            throw new IllegalArgumentException(
                    "Exact current capture-v2 supersession evidence is required"
            );
        }
    }
}
