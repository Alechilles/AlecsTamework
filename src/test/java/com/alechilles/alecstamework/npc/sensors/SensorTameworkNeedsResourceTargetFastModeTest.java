package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.joml.Vector3d;
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

    @Test
    void fastConsumeTargetMarkerExpiresAndClearsOnRelease() {
        SensorTameworkNeedsResourceTarget.clearFastConsumeTargetsForTests();
        UUID npc = new UUID(0L, 302L);
        Vector3d target = new Vector3d(4.2, 65.0, -9.8);

        SensorTameworkNeedsResourceTarget.rememberFastConsumeTargetForTests(
                npc,
                "world-a",
                "Water",
                target,
                2_000L
        );

        assertTrue(SensorTameworkNeedsResourceTarget.isFastConsumeTargetForTests(
                npc,
                "world-a",
                "Water",
                target,
                1_500L
        ));
        assertFalse(SensorTameworkNeedsResourceTarget.isFastConsumeTargetForTests(
                npc,
                "world-a",
                "Water",
                target,
                2_000L
        ));

        SensorTameworkNeedsResourceTarget.rememberFastConsumeTargetForTests(
                npc,
                "world-a",
                "Water",
                target,
                4_000L
        );
        SensorTameworkNeedsResourceTarget.releaseTargetForTests(npc, "world-a", "Water", target);

        assertFalse(SensorTameworkNeedsResourceTarget.isFastConsumeTargetForTests(
                npc,
                "world-a",
                "Water",
                target,
                3_000L
        ));
    }
}
