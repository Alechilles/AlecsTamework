package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

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

    @Test
    void deadTargetDiagnosticIncludesExactCaptureEvidence() {
        UUID player = UUID.fromString(
                "10000000-0000-0000-0000-000000000001");
        UUID target = UUID.fromString(
                "20000000-0000-0000-0000-000000000001");

        String diagnostic = SpawnerCapturePolicyService.deadTargetDiagnostic(
                player, target, "Dragonling_Blue", "Dragonling_Crate",
                new SpawnerCapturePolicyService.CaptureHealth(0.0D, 50.0D),
                true
        );

        assertTrue(diagnostic.contains("player=" + player));
        assertTrue(diagnostic.contains("target=" + target));
        assertTrue(diagnostic.contains("role=Dragonling_Blue"));
        assertTrue(diagnostic.contains("item=Dragonling_Crate"));
        assertTrue(diagnostic.contains("health=0.0/50.0"));
        assertTrue(diagnostic.contains("deathComponent=true"));
    }
}
