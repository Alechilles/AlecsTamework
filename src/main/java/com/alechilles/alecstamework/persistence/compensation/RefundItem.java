package com.alechilles.alecstamework.persistence.compensation;

import javax.annotation.Nonnull;

/** One normalized item-and-quantity line in an ordered refund recipe. */
public record RefundItem(@Nonnull String itemId, int quantity) {
    public RefundItem {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("Refund item is required");
        }
        itemId = itemId.trim();
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Refund quantity must be positive"
            );
        }
    }
}
