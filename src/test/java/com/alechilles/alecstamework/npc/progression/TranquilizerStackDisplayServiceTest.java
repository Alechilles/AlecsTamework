package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TranquilizerStackDisplayServiceTest {
    @Test
    void computeStacksRoundsFromThirtySecondStackDuration() {
        Assertions.assertEquals(0, TranquilizerStackDisplayService.computeStacks(0.0));
        Assertions.assertEquals(1, TranquilizerStackDisplayService.computeStacks(30.0));
        Assertions.assertEquals(3, TranquilizerStackDisplayService.computeStacks(80.0));
        Assertions.assertEquals(4, TranquilizerStackDisplayService.computeStacks(105.0));
    }

    @Test
    void resolvesPeakDurationFromTrackedAndCurrentValues() {
        Assertions.assertEquals(90.0, TranquilizerStackDisplayService.resolvePeakDuration(90.0, 30.0));
        Assertions.assertEquals(45.0, TranquilizerStackDisplayService.resolvePeakDuration(0.0, 45.0));
        Assertions.assertEquals(0.0, TranquilizerStackDisplayService.resolvePeakDuration(-5.0, Double.NaN));
    }

    @Test
    void formatsRemainingDurationForHudAndNameplates() {
        Assertions.assertEquals("0s", TranquilizerStackDisplayService.formatRemainingDuration(0.0));
        Assertions.assertEquals("12s", TranquilizerStackDisplayService.formatRemainingDuration(11.2));
        Assertions.assertEquals("1m 5s", TranquilizerStackDisplayService.formatRemainingDuration(64.2));
    }

    @Test
    void formatsCombinedVariant() {
        Assertions.assertEquals(
                "3 (1m 45s)",
                TranquilizerStackDisplayService.formatStackValue(3, "1m 45s", 0)
        );
        Assertions.assertEquals("3", TranquilizerStackDisplayService.formatStackValue(3, "1m 45s", 1));
        Assertions.assertEquals("1m 45s", TranquilizerStackDisplayService.formatStackValue(3, "1m 45s", 2));
    }
}
