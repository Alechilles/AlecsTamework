package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests priority comparison logic for needs-seek resource selection. */
class SensorTameworkNeedLowestTest {

    @Test
    void selectedNeedWinsWhenLower() {
        assertTrue(SensorTameworkNeedLowest.isSelectedNeedLowestForTests(0.0, 0.849));
        assertTrue(SensorTameworkNeedLowest.isSelectedNeedLowestForTests(0.25, 0.75));
    }

    @Test
    void selectedNeedWinsTieForStableBranchOrdering() {
        assertTrue(SensorTameworkNeedLowest.isSelectedNeedLowestForTests(0.5, 0.5));
    }

    @Test
    void selectedNeedLosesWhenOtherNeedIsLower() {
        assertFalse(SensorTameworkNeedLowest.isSelectedNeedLowestForTests(0.849, 0.0));
        assertFalse(SensorTameworkNeedLowest.isSelectedNeedLowestForTests(0.75, 0.25));
    }

    @Test
    void invalidRatiosDoNotMatch() {
        assertFalse(SensorTameworkNeedLowest.isSelectedNeedLowestForTests(Double.NaN, 0.0));
        assertFalse(SensorTameworkNeedLowest.isSelectedNeedLowestForTests(0.0, Double.NaN));
    }
}
