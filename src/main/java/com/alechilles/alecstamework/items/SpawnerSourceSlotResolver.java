package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves an exact spawner source after players rearrange hotbar items during async admission. */
final class SpawnerSourceSlotResolver {
    private SpawnerSourceSlotResolver() {
    }

    @Nullable
    static <T> Integer resolve(
            int capacity,
            @Nonnull IntFunction<T> itemAt,
            @Nonnull T expected,
            @Nullable Integer preferredSlot
    ) {
        Objects.requireNonNull(expected, "expected");
        return resolveMatching(
                capacity, itemAt, item -> Objects.equals(expected, item), preferredSlot
        );
    }

    @Nullable
    static <T> Integer resolveMatching(
            int capacity,
            @Nonnull IntFunction<T> itemAt,
            @Nonnull Predicate<T> matches,
            @Nullable Integer preferredSlot
    ) {
        Objects.requireNonNull(itemAt, "itemAt");
        Objects.requireNonNull(matches, "matches");
        if (isValid(preferredSlot, capacity)
                && matches.test(itemAt.apply(preferredSlot))) {
            return preferredSlot;
        }
        Integer match = null;
        for (int slot = 0; slot < capacity; slot++) {
            if (!matches.test(itemAt.apply(slot))) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = slot;
        }
        return match;
    }

    private static boolean isValid(@Nullable Integer slot, int capacity) {
        return slot != null && slot >= 0 && slot < capacity;
    }
}
