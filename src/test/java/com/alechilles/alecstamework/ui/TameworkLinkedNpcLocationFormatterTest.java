package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TameworkLinkedNpcLocationFormatterTest {
    @Test
    void reportsRoundedOffsetsFromTheViewerRatherThanTheOrigin() {
        assertOffset(-780.2, -1550.3, "1550m north, 780m west");
        assertOffset(780.2, -1550.3, "1550m north, 780m east");
        assertOffset(-780.2, 1550.3, "1550m south, 780m west");
        assertOffset(780.2, 1550.3, "1550m south, 780m east");
        assertOffset(0.2, -1550.3, "1550m north");
        assertOffset(0.2, 1550.3, "1550m south");
        assertOffset(-780.2, 0.2, "780m west");
        assertOffset(780.2, 0.2, "780m east");
        assertOffset(0.2, -0.2, "Less than 1m away");
    }

    private static void assertOffset(double dx, double dz, String expected) {
        assertEquals(expected, TameworkLinkedNpcLocationFormatter.formatRelativeDistance(
                "en-US", "default", -2000, 3000, "default", -2000 + dx, 3000 + dz));
    }

    @Test
    void hidesOffsetsForDifferentInstancesAndUnknownPositions() {
        String first = "instance-Garden-11111111-1111-1111-1111-111111111111";
        String second = "instance-Garden-22222222-2222-2222-2222-222222222222";
        assertEquals("", TameworkLinkedNpcLocationFormatter.formatRelativeDistance(
                "en-US", first, 0, 0, second, 50, 50));
        assertEquals("", TameworkLinkedNpcLocationFormatter.formatRelativeDistance(
                "en-US", "default", Double.NaN, 0, "default", 50, 50));
        assertEquals("", TameworkLinkedNpcLocationFormatter.formatRelativeDistance(
                "en-US", "default", 0, 0, "default", 50, Double.NaN));
        assertEquals("", TameworkLinkedNpcLocationFormatter.formatRelativeDistance(
                "en-US", "", 0, 0, "", 50, 50));
    }

    @Test
    void formatsInTheViewersLanguageWithFallback() {
        assertEquals("1550 m nach Norden, 780 m nach Westen",
                TameworkLinkedNpcLocationFormatter.formatRelativeDistance(
                        "de-DE", "default", 0, 0, "default", -780, -1550));
        assertEquals("1550m north, 780m west",
                TameworkLinkedNpcLocationFormatter.formatRelativeDistance(
                        "unknown", "default", 0, 0, "default", -780, -1550));
    }
}
