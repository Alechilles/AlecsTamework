package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CommandCompanionPlacementServiceTest {
    @Test
    void recallPlacementPrefersFrontArcBeforeSidesAndRear() {
        assertArrayEquals(new double[] {
                0.0, 30.0, -30.0, 45.0, -45.0, 60.0, -60.0,
                90.0, -90.0, 120.0, -120.0, 150.0, -150.0, 180.0
        }, CommandCompanionPlacementService.resolvePlacementAngleOffsets());
    }
}
