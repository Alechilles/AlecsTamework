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
        assertTrue(NeedsResourceTargetStateFacade.shouldBypassPathPreflightForTests(true, true));
        assertFalse(NeedsResourceTargetStateFacade.shouldBypassPathPreflightForTests(true, false));
        assertFalse(NeedsResourceTargetStateFacade.shouldBypassPathPreflightForTests(false, true));
    }

    @Test
    void fastModeUsesDiagnosticReason() {
        assertEquals(
                "food_target_search_primary_fast_consume",
                NeedsResourceTargetStateFacade.fastModeReasonForTests("food_target_search_primary")
        );
    }

    @Test
    void fastConsumeTargetMarkerExpiresAndClearsOnRelease() {
        NeedsResourceTargetStateFacade.clearFastConsumeTargetsForTests();
        UUID npc = new UUID(0L, 302L);
        Vector3d target = new Vector3d(4.2, 65.0, -9.8);

        NeedsResourceTargetStateFacade.rememberFastConsumeTargetForTests(
                npc,
                "world-a",
                "Water",
                target,
                2_000L
        );

        assertTrue(NeedsResourceTargetStateFacade.isFastConsumeTargetForTests(
                npc,
                "world-a",
                "Water",
                target,
                1_500L
        ));
        assertFalse(NeedsResourceTargetStateFacade.isFastConsumeTargetForTests(
                npc,
                "world-a",
                "Water",
                target,
                2_000L
        ));

        NeedsResourceTargetStateFacade.rememberFastConsumeTargetForTests(
                npc,
                "world-a",
                "Water",
                target,
                4_000L
        );
        NeedsResourceTargetStateFacade.releaseTargetForTests(npc, "world-a", "Water", target);

        assertFalse(NeedsResourceTargetStateFacade.isFastConsumeTargetForTests(
                npc,
                "world-a",
                "Water",
                target,
                3_000L
        ));
    }
}
