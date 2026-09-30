package com.alechilles.alecstamework.companion.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionLocationTest {
    @Test
    void rejectsLocationsMissingTheirKeyFields() {
        assertThrows(IllegalArgumentException.class, () -> new CompanionLocation(LocationKind.LIVE, null, 0, 0, 0, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CompanionLocation(LocationKind.COOP, "default", 1, 2, 3, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CompanionLocation(LocationKind.STORED, null, 0, 0, 0, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CompanionLocation(LocationKind.DEAD, null, 0, 0, 0, -1, StoredReason.ROSTER, "fall"));
    }

    @Test
    void factoriesProduceValidLocations() {
        assertEquals(LocationKind.COOP, CompanionLocation.coop("default", 10, 64, -5, 2).kind());
        assertEquals(StoredReason.BONDED, CompanionLocation.stored(StoredReason.BONDED).reason());
        assertEquals("default", CompanionLocation.live("default", 1.5, 64, -2.5).world());
    }
}
