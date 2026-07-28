package com.alechilles.alecstamework.companion.revival;

import javax.annotation.Nonnull;

/** One unique item-and-quantity line in an ordered revival charge. */
public record RevivalCostItem(
        @Nonnull String itemId,
        int quantity
) {
    public RevivalCostItem {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException(
                    "Revival cost item is required"
            );
        }
        itemId = itemId.trim();
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Revival cost quantity must be positive"
            );
        }
    }
}
