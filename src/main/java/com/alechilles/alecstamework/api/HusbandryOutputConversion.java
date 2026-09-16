package com.alechilles.alecstamework.api;

import javax.annotation.Nullable;

/**
 * One optional, provider-supplied conversion applied after a husbandry output
 * quantity has already been resolved.
 *
 * <p>The runtime treats invalid values as no conversion. Providers do not
 * materialize items themselves.
 */
public record HusbandryOutputConversion(
        @Nullable String inputItemId,
        @Nullable String outputItemId,
        int inputQuantity,
        int outputQuantity,
        double chance
) {
    /** Keeps one converted stack bounded even if a provider returns malformed data. */
    public static final int MAX_BATCH_QUANTITY = 64;

    /** Returns whether this conversion can safely consume a complete input batch. */
    public boolean valid() {
        return inputItemId != null && !inputItemId.isBlank()
                && outputItemId != null && !outputItemId.isBlank()
                && !inputItemId.trim().equalsIgnoreCase(outputItemId.trim())
                && inputQuantity > 0 && inputQuantity <= MAX_BATCH_QUANTITY
                && outputQuantity > 0 && outputQuantity <= MAX_BATCH_QUANTITY
                && Double.isFinite(chance) && chance > 0.0 && chance <= 1.0;
    }
}
