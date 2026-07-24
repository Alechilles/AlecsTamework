package com.alechilles.alecstamework.persistence.compensation;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One deterministic, receipt-addressable item recipe owned by a persistence operation.
 *
 * <p>The operation ID is the claim identity. Delivery is complete only when positive external
 * receipt evidence and its timestamp are committed together; absence never proves delivery.</p>
 */
public record RefundClaim(@Nonnull OperationId operationId,
                          @Nonnull UUID recipientUuid,
                          @Nonnull String recipientWorldKey,
                          @Nonnull List<RefundItem> items,
                          @Nonnull String reasonCode,
                          @Nonnull String receiptKey,
                          long claimedAtMs,
                          @Nullable String deliveryEvidence,
                          @Nullable Long deliveredAtMs) {
    public RefundClaim {
        if (operationId == null || recipientUuid == null) {
            throw new IllegalArgumentException("Refund operation and recipient are required");
        }
        recipientWorldKey = requireText(
                recipientWorldKey,
                "Refund recipient world"
        );
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "Refund recipe requires at least one item"
            );
        }
        items = List.copyOf(items);
        HashSet<String> itemIds = new HashSet<>();
        for (RefundItem item : items) {
            if (item == null || !itemIds.add(item.itemId())) {
                throw new IllegalArgumentException(
                        "Refund recipe item IDs must be unique"
                );
            }
        }
        reasonCode = requireText(reasonCode, "Refund reason");
        receiptKey = requireText(receiptKey, "Refund receipt");
        if ((deliveryEvidence == null) != (deliveredAtMs == null)) {
            throw new IllegalArgumentException(
                    "Refund delivery evidence and timestamp must be recorded together"
            );
        }
        if (deliveryEvidence != null) {
            deliveryEvidence = requireText(
                    deliveryEvidence,
                    "Refund delivery evidence"
            );
        }
    }

    /** Returns whether positive delivery evidence is durable. */
    public boolean delivered() {
        return deliveredAtMs != null;
    }

    /** Returns the same claim with one exact delivery receipt. */
    @Nonnull
    public RefundClaim delivered(@Nonnull String evidence, long deliveredAt) {
        if (delivered()) {
            if (deliveryEvidence.equals(evidence)
                    && deliveredAtMs.longValue() == deliveredAt) {
                return this;
            }
            throw new IllegalStateException("Refund claim is already delivered");
        }
        return new RefundClaim(
                operationId,
                recipientUuid,
                recipientWorldKey,
                items,
                reasonCode,
                receiptKey,
                claimedAtMs,
                evidence,
                deliveredAt
        );
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
