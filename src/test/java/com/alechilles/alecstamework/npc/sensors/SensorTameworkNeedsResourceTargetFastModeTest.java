package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SensorTameworkNeedsResourceTargetFastModeTest {
    @Test
    void fastModeBypassesPathPreflightOnlyWhenTargetExists() {
        assertTrue(SensorTameworkNeedsResourceTarget.shouldBypassPathPreflightForTests(true, true));
        assertFalse(SensorTameworkNeedsResourceTarget.shouldBypassPathPreflightForTests(true, false));
        assertFalse(SensorTameworkNeedsResourceTarget.shouldBypassPathPreflightForTests(false, true));
    }

    @Test
    void fastModeUsesDiagnosticReason() {
        assertEquals(
                "food_target_search_primary_fast_consume",
                SensorTameworkNeedsResourceTarget.fastModeReasonForTests("food_target_search_primary")
        );
    }
}
