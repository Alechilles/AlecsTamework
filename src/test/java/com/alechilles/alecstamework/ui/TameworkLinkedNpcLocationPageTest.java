package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TameworkLinkedNpcLocationPageTest {

    @Test
    void formatDisplayWorldNameHidesInstanceUuid() {
        assertEquals(
                "Portals_Hedera (Instance)",
                TameworkLinkedNpcLocationFormatter.formatDisplayWorldName(
                        "instance-Portals_Hedera-eccce206-7b1b-4f9f-b047-506895b403e1",
                        "unknown world"
                )
        );
    }

    @Test
    void formatDisplayWorldNameHidesPlayerInstanceSuffix() {
        assertEquals(
                "ahdemo (Instance)",
                TameworkLinkedNpcLocationFormatter.formatDisplayWorldName(
                        "ahdemo-4f0181d6-516c-4fd4-b366-f606d9bb864a-0a63e3511481",
                        "unknown world"
                )
        );
    }

    @Test
    void formatDisplayWorldNameKeepsNormalWorldName() {
        assertEquals(
                "adventure_world",
                TameworkLinkedNpcLocationFormatter.formatDisplayWorldName("adventure_world", "unknown world")
        );
    }

    @Test
    void formatCoordinatesUsesCopyableTuple() {
        assertEquals("12.3, 64.0, -8.9", TameworkLinkedNpcLocationFormatter.formatCoordinates(12.34, 64.0, -8.91));
    }
}
