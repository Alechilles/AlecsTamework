package com.alechilles.alecstamework.npc.progression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One committed autonomous food or water need change. */
record NeedsSatisfactionOutcome(
        @Nonnull String needType,
        @Nonnull String resourceSource,
        @Nonnull String resourceId,
        double previousValue,
        double currentValue,
        double restoredAmount
) {
    private static final double EPSILON = 1.0E-6;

    public NeedsSatisfactionOutcome {
        needType = requireText(needType, "needType");
        resourceSource = requireText(resourceSource, "resourceSource");
        resourceId = requireText(resourceId, "resourceId");
        if (!Double.isFinite(previousValue) || !Double.isFinite(currentValue)
                || !Double.isFinite(restoredAmount) || restoredAmount < 0.0) {
            throw new IllegalArgumentException(
                    "Need values must be finite and restoredAmount cannot be negative.");
        }
    }

    /** Resolves only the need changes supported by committed resource evidence. */
    @Nonnull
    public static List<NeedsSatisfactionOutcome> resolveCommitted(
            double previousHunger,
            double currentHunger,
            double previousThirst,
            double currentThirst,
            @Nullable Map<String, Integer> consumedFoodItems,
            boolean foodHappinessChanged,
            @Nullable String waterSource
    ) {
        List<NeedsSatisfactionOutcome> outcomes = new ArrayList<>(2);
        String foodItemId = primaryConsumedItem(consumedFoodItems);
        if (foodItemId != null
                && (currentHunger > previousHunger + EPSILON || foodHappinessChanged)) {
            outcomes.add(new NeedsSatisfactionOutcome(
                    "hunger",
                    "container",
                    foodItemId,
                    previousHunger,
                    currentHunger,
                    Math.max(0.0, currentHunger - previousHunger)
            ));
        }
        if (waterSource != null
                && !waterSource.isBlank()
                && currentThirst > previousThirst + EPSILON) {
            outcomes.add(new NeedsSatisfactionOutcome(
                    "thirst",
                    waterSource,
                    "water",
                    previousThirst,
                    currentThirst,
                    Math.max(0.0, currentThirst - previousThirst)
            ));
        }
        return List.copyOf(outcomes);
    }

    @Nullable
    private static String primaryConsumedItem(@Nullable Map<String, Integer> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return items.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .map(entry -> entry.getKey().trim())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .findFirst()
                .orElse(null);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
