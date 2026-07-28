package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;

/**
 * Exact physical receipt written to a coop block before one live source is retired.
 *
 * <p>The receipt is evidence for only one operation, profile, source alias, and physical slot.
 * It is never inferred from entity absence.</p>
 */
public record CoopCaptureReceipt(
        @Nonnull OperationId operationId,
        @Nonnull ProfileId profileId,
        @Nonnull NpcAlias sourceAlias,
        @Nonnull CoopSlotKey slotKey,
        @Nonnull String receiptKey
) {
    public CoopCaptureReceipt {
        if (operationId == null || profileId == null || sourceAlias == null
                || slotKey == null || receiptKey == null
                || receiptKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Complete coop capture receipt is required"
            );
        }
        receiptKey = receiptKey.trim();
    }

    /** Builds the only receipt accepted for the supplied prepared operation. */
    @Nonnull
    public static CoopCaptureReceipt exact(
            @Nonnull CompanionCoopCaptureRequest request,
            @Nonnull OperationId operationId
    ) {
        if (request == null || operationId == null) {
            throw new IllegalArgumentException(
                    "Coop capture request and operation are required"
            );
        }
        return new CoopCaptureReceipt(
                operationId,
                request.profileId(),
                request.source().sourceAlias(),
                request.targetSlot(),
                request.source().retirementReceiptKey()
        );
    }
}
