package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import javax.annotation.Nonnull;

/** Positive, receipt-correlated evidence for retiring one live entity into a coop. */
public record CoopCaptureSourceEvidence(
        @Nonnull NpcAlias sourceAlias,
        @Nonnull String sourceWorldKey,
        @Nonnull String retirementReceiptKey
) implements CoopCaptureSource {
    public CoopCaptureSourceEvidence {
        if (sourceAlias == null) {
            throw new IllegalArgumentException("Coop capture source alias is required");
        }
        sourceWorldKey = requireText(sourceWorldKey, "Coop capture source world");
        retirementReceiptKey = requireText(
                retirementReceiptKey, "Coop capture retirement receipt"
        );
    }

    @Override
    @Nonnull
    public Kind kind() {
        return Kind.LIVE_ENTITY;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
