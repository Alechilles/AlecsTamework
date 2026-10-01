package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntPredicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure coop occupancy (spec 6.8 rule 4). An entry counts only if it holds an unowned resident or
 * its record is COOP at this block and slot with the same generation. Any other entry is stale:
 * its companion moved on (a Recover, say), so the slot is free and the entry may be overwritten.
 */
public final class CoopSlots {
    private CoopSlots() {
    }

    /** {@code record} is the index record of the entry's profile, null when it has none. */
    public static boolean occupied(@Nonnull TameworkCoopSlotsComponent.Slot entry, @Nullable CompanionRecord record,
                                   @Nonnull String world, int x, int y, int z) {
        if (entry.unownedEntity() != null) {
            return true;
        }
        if (record == null || !record.profileId().equals(entry.profileId())
                || record.generation() != entry.generation()) {
            return false;
        }
        CompanionLocation at = record.location();
        return at.kind() == LocationKind.COOP && world.equals(at.world())
                && at.x() == x && at.y() == y && at.z() == z && at.slot() == entry.slot();
    }

    /** The lowest free slot in [0, maxResidents), or -1. */
    public static int firstFree(@Nonnull List<TameworkCoopSlotsComponent.Slot> entries, int maxResidents,
                                @Nonnull Function<UUID, CompanionRecord> records, @Nonnull String world,
                                int x, int y, int z) {
        return firstFree(entries, maxResidents, records, world, x, y, z, slot -> false);
    }

    /** Like {@link #firstFree(List, int, Function, String, int, int, int)}, also skipping {@code reserved} slots. */
    public static int firstFree(@Nonnull List<TameworkCoopSlotsComponent.Slot> entries, int maxResidents,
                                @Nonnull Function<UUID, CompanionRecord> records, @Nonnull String world,
                                int x, int y, int z, @Nonnull IntPredicate reserved) {
        for (int slot = 0; slot < maxResidents; slot++) {
            if (!reserved.test(slot) && !taken(entries, slot, records, world, x, y, z)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean taken(List<TameworkCoopSlotsComponent.Slot> entries, int slot,
                                 Function<UUID, CompanionRecord> records, String world, int x, int y, int z) {
        for (TameworkCoopSlotsComponent.Slot entry : entries) {
            if (entry.slot() == slot) {
                CompanionRecord record = entry.profileId() == null ? null : records.apply(entry.profileId());
                return occupied(entry, record, world, x, y, z);
            }
        }
        return false;
    }
}
