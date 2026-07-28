package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import javax.annotation.Nonnull;

/**
 * Immutable source evidence for one coop capture.
 *
 * <p>Live entities and captured inventory artifacts share only the identity needed by the
 * canonical coop receipt. Source-specific proof remains on the concrete variant.</p>
 */
public sealed interface CoopCaptureSource
        permits CoopCaptureSourceEvidence, CoopCapturedItemSourceEvidence {
    enum Kind {
        LIVE_ENTITY,
        CAPTURED_ITEM
    }

    @Nonnull
    Kind kind();

    @Nonnull
    NpcAlias sourceAlias();

    @Nonnull
    String sourceWorldKey();

    @Nonnull
    String retirementReceiptKey();
}
