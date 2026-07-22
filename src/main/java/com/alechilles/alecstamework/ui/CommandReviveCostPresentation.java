package com.alechilles.alecstamework.ui;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable linked-panel presentation of a server-generated multi-item revival quote. */
public record CommandReviveCostPresentation(@Nonnull List<CostLine> costs,
                                             @Nonnull String configRevision,
                                             @Nullable String insufficientMessageKey) {
    public CommandReviveCostPresentation {
        costs = List.copyOf(Objects.requireNonNull(costs, "costs"));
        configRevision = requireText(configRevision, "configRevision");
        insufficientMessageKey = normalize(insufficientMessageKey);
    }

    public boolean affordable() {
        return costs.stream().allMatch(CostLine::satisfied);
    }

    public int missingComponentCount() {
        return (int) costs.stream().filter(line -> !line.satisfied()).count();
    }

    public record CostLine(@Nonnull String itemId,
                           @Nonnull String localizedName,
                           @Nullable String iconAssetId,
                           int ownedQuantity,
                           int requiredQuantity) {
        public CostLine {
            itemId = requireText(itemId, "itemId");
            localizedName = requireText(localizedName, "localizedName");
            iconAssetId = normalize(iconAssetId);
            if (ownedQuantity < 0) throw new IllegalArgumentException("ownedQuantity must be non-negative");
            if (requiredQuantity <= 0) throw new IllegalArgumentException("requiredQuantity must be positive");
        }

        public int shortageQuantity() {
            return Math.max(0, requiredQuantity - ownedQuantity);
        }

        public boolean satisfied() {
            return shortageQuantity() == 0;
        }
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
