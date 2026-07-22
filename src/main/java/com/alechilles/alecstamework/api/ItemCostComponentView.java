package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable item-agnostic component of an ordered AND cost. */
public record ItemCostComponentView(@Nonnull String itemId, int quantity) {
    public ItemCostComponentView {
        itemId = requireText(itemId, "itemId");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
