package com.alechilles.alecstamework.api;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provider-supplied base reward roll for one husbandry action.
 *
 * <p>An empty table is an explicit answer: the action rolls nothing. Return
 * {@code null} from the provider instead to keep the configured drop list.
 * Null entries are dropped and the table keeps at most {@link #MAX_ENTRIES}
 * entries in their given order.</p>
 */
public record HusbandryRewardTable(@Nonnull List<HusbandryRewardEntry> entries) {
    /** Upper bound for the number of entries Tamework rolls from one table. */
    public static final int MAX_ENTRIES = 64;

    private static final HusbandryRewardTable EMPTY = new HusbandryRewardTable(List.of());

    public HusbandryRewardTable(@Nullable List<HusbandryRewardEntry> entries) {
        ArrayList<HusbandryRewardEntry> copy = new ArrayList<>();
        if (entries != null) {
            for (HusbandryRewardEntry entry : entries) {
                if (entry != null && copy.size() < MAX_ENTRIES) {
                    copy.add(entry);
                }
            }
        }
        this.entries = List.copyOf(copy);
    }

    /** Returns the shared table that rolls nothing. */
    @Nonnull
    public static HusbandryRewardTable empty() {
        return EMPTY;
    }
}
