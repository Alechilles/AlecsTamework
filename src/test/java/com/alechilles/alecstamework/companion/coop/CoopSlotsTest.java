package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSlotsTest {
    private static final String WORLD = "default";

    private static CompanionRecord record(UUID profileId, long generation, CompanionLocation location) {
        return CompanionRecord.builder(profileId, "Tamed_Chicken", location).generation(generation).build();
    }

    private static CompanionRecord cooped(UUID profileId, long generation, int slot) {
        return record(profileId, generation, CompanionLocation.coop(WORLD, 4, 64, 9, slot));
    }

    @Test
    void anEntryWhoseCompanionMovedOnOrChangedGenerationDoesNotOccupyItsSlot() {
        UUID id = UUID.randomUUID();
        TameworkCoopSlotsComponent.Slot entry = TameworkCoopSlotsComponent.Slot.companion(1, id, 3);

        assertTrue(CoopSlots.occupied(entry, cooped(id, 3, 1), WORLD, 4, 64, 9));
        assertFalse(CoopSlots.occupied(entry, record(id, 3, CompanionLocation.live(WORLD, 0, 0, 0)), WORLD, 4, 64, 9));
        assertFalse(CoopSlots.occupied(entry, cooped(id, 4, 1), WORLD, 4, 64, 9));
        assertFalse(CoopSlots.occupied(entry, cooped(id, 3, 2), WORLD, 4, 64, 9), "recorded in another slot");
        assertFalse(CoopSlots.occupied(entry, cooped(id, 3, 1), WORLD, 5, 64, 9), "recorded at another coop");
        assertFalse(CoopSlots.occupied(entry, null, WORLD, 4, 64, 9));
    }

    @Test
    void anUnownedEntryAlwaysOccupiesItsSlot() {
        TameworkCoopSlotsComponent.Slot entry = TameworkCoopSlotsComponent.Slot.unowned(0, new BsonDocument());

        assertTrue(CoopSlots.occupied(entry, null, WORLD, 4, 64, 9));
    }

    @Test
    void firstFreeSkipsOccupiedSlotsAndReturnsMinusOneWhenFull() {
        UUID current = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        Map<UUID, CompanionRecord> records = new HashMap<>();
        records.put(current, cooped(current, 0, 1));
        records.put(stale, cooped(stale, 5, 2));
        List<TameworkCoopSlotsComponent.Slot> entries = List.of(
                TameworkCoopSlotsComponent.Slot.unowned(0, new BsonDocument()),
                TameworkCoopSlotsComponent.Slot.companion(1, current, 0),
                TameworkCoopSlotsComponent.Slot.companion(2, stale, 4));

        assertEquals(2, CoopSlots.firstFree(entries, 3, records::get, WORLD, 4, 64, 9));
        assertEquals(-1, CoopSlots.firstFree(entries.subList(0, 2), 2, records::get, WORLD, 4, 64, 9));
    }
}
