package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One server-authoritative cost line rendered by command revival confirmation. */
public record PaidCommandRevivalCostQuoteView(@Nonnull String itemId,
                                               int requiredQuantity,
                                               int ownedQuantity,
                                               @Nullable String localizedName,
                                               @Nullable String iconAssetId) {
    public PaidCommandRevivalCostQuoteView {
        itemId = requireText(itemId, "itemId");
        if (requiredQuantity <= 0) throw new IllegalArgumentException("requiredQuantity must be positive");
        if (ownedQuantity < 0) throw new IllegalArgumentException("ownedQuantity must be non-negative");
        localizedName = normalize(localizedName);
        iconAssetId = normalize(iconAssetId);
    }

    public int shortageQuantity() {
        return Math.max(0, requiredQuantity - ownedQuantity);
    }

    public boolean satisfied() {
        return shortageQuantity() == 0;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
