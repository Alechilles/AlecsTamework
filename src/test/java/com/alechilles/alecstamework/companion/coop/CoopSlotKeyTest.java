package com.alechilles.alecstamework.companion.coop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for collision-free replacement coop slot identities. */
class CoopSlotKeyTest {
    @Test
    void roundTripsUnicodeAndDelimiterBearingIdentifiers() {
        CoopSlotKey key = new CoopSlotKey(
                "world:one|Ω", "coop:two|β", -10, 64, 20, 3
        );

        assertEquals(key, CoopSlotKey.parse(key.toString()));
        assertEquals("world:one|Ω|coop:two|β|-10|64|20|3",
                key.legacySourceKey());
    }

    @Test
    void delimiterPlacementCannotAliasAnotherSlot() {
        CoopSlotKey first = new CoopSlotKey("a|b", "c", 1, 2, 3, 0);
        CoopSlotKey second = new CoopSlotKey("a", "b|c", 1, 2, 3, 0);

        assertNotEquals(first.toString(), second.toString());
        assertThrows(
                IllegalArgumentException.class,
                () -> CoopSlotKey.parse(first.legacySourceKey())
        );
    }
}
