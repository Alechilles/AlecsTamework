package com.alechilles.alecstamework.companion.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionLocationTest {
    @Test
    void rejectsLocationsMissingTheirKeyFields() {
        assertThrows(IllegalArgumentException.class, () -> new CompanionLocation(LocationKind.LIVE, null, 0, 0, 0, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CompanionLocation(LocationKind.COOP, "default", 1, 2, 3, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CompanionLocation(LocationKind.STORED, null, 0, 0, 0, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CompanionLocation(LocationKind.DEAD, null, 0, 0, 0, -1, StoredReason.ROSTER, "fall"));
    }
}
