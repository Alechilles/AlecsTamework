package com.alechilles.alecstamework.api;

import javax.annotation.Nullable;

/**
 * One independently rolled item of a provider-supplied husbandry reward table.
 *
 * <p>Tamework rolls {@code chance} once. On success it drops a uniform quantity
 * from {@code min} to {@code max}, both inclusive. The constructor bounds
 * malformed values instead of rejecting them: quantities are clamped to
 * {@code 0..MAX_QUANTITY} with {@code max >= min}, and a non-finite chance
 * becomes {@code 0}. An entry that can never drop is ignored.</p>
 */
public record HusbandryRewardEntry(
        @Nullable String itemId,
        int min,
        int max,
        double chance
) {
    /** Upper bound for one entry's rolled quantity. */
    public static final int MAX_QUANTITY = 1000;

    public HusbandryRewardEntry {
        itemId = itemId == null || itemId.isBlank() ? null : itemId.trim();
        min = Math.max(0, Math.min(MAX_QUANTITY, min));
        max = Math.max(min, Math.min(MAX_QUANTITY, max));
        chance = Double.isFinite(chance) ? Math.max(0.0, Math.min(1.0, chance)) : 0.0;
    }

    /** Returns whether this entry names an item and can drop at least one of it. */
    public boolean valid() {
        return itemId != null && max > 0 && chance > 0.0;
    }
}
