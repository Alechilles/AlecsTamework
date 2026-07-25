package com.alechilles.alecstamework.persistence.runtime.player;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import javax.annotation.Nonnull;

/**
 * Persistent proof that one exact inventory mutation plan was admitted.
 *
 * <p>The immutable operation payload retains the source and target stack
 * evidence. This receipt binds that evidence to a player save without
 * duplicating operation-specific inventory state in ECS.</p>
 */
public record InventoryOperationReceipt(
        @Nonnull String receiptKey,
        @Nonnull OperationId operationId,
        @Nonnull OperationKind operationKind,
        @Nonnull Sha256Hash planHash,
        long installedAtMs
) implements Comparable<InventoryOperationReceipt> {
    public InventoryOperationReceipt {
        if (receiptKey == null || receiptKey.isBlank()
                || operationId == null || operationKind == null
                || planHash == null) {
            throw new IllegalArgumentException(
                    "Complete inventory operation receipt is required"
            );
        }
        receiptKey = receiptKey.trim();
    }

    @Override
    public int compareTo(InventoryOperationReceipt other) {
        if (other == null) {
            throw new NullPointerException(
                    "Other inventory operation receipt is required"
            );
        }
        return receiptKey.compareTo(other.receiptKey);
    }
}
