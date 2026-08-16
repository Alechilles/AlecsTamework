package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpawnerCaptureLivingTargetPolicyTest {
    @Test
    void rejectsDeadTargetsWithoutRejectingInjuredLivingTargets() {
        assertFalse(SpawnerCapturePolicyService.isLivingCaptureTarget(
                new SpawnerCapturePolicyService.CaptureHealth(0.0D, 50.0D),
                false
        ));
        assertFalse(SpawnerCapturePolicyService.isLivingCaptureTarget(
                new SpawnerCapturePolicyService.CaptureHealth(1.0D, 50.0D),
                true
        ));
        assertTrue(SpawnerCapturePolicyService.isLivingCaptureTarget(
                new SpawnerCapturePolicyService.CaptureHealth(1.0D, 50.0D),
                false
        ));
    }
}
