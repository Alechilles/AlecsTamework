package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** One immutable, ordered item requirement for a bonded companion revival. */
public record BondedCompanionReviveCost(@Nonnull String itemId, int quantity) {
    public BondedCompanionReviveCost {
        itemId = Objects.requireNonNull(itemId, "itemId").trim();
        if (itemId.isEmpty()) {
            throw new IllegalArgumentException("itemId is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
