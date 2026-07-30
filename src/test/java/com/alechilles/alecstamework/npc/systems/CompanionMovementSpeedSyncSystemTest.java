package com.alechilles.alecstamework.npc.systems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.alechilles.alecstamework.config.assets.TwCompanionMovementConfig;
import com.alechilles.alecstamework.npc.progression.CompanionMovementSpeedResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for companion movement-speed lifecycle change detection. */
class CompanionMovementSpeedSyncSystemTest {
    @Test
    void lifecycleCompletionCommitsOnlyWhenNpcAndCurrentMountedRiderRefreshesSucceed() {
        assertTrue(CompanionMovementSpeedSyncSystem.isRefreshComplete(
                new CompanionMovementSpeedSyncSystem.RefreshCompletion(true, false, false, false)));
        assertTrue(CompanionMovementSpeedSyncSystem.isRefreshComplete(
                new CompanionMovementSpeedSyncSystem.RefreshCompletion(true, true, true, true)));
        assertFalse(CompanionMovementSpeedSyncSystem.isRefreshComplete(
                new CompanionMovementSpeedSyncSystem.RefreshCompletion(false, false, false, false)));
        assertFalse(CompanionMovementSpeedSyncSystem.isRefreshComplete(
                new CompanionMovementSpeedSyncSystem.RefreshCompletion(true, true, false, false)));
        assertFalse(CompanionMovementSpeedSyncSystem.isRefreshComplete(
                new CompanionMovementSpeedSyncSystem.RefreshCompletion(true, true, true, false)));
    }

    @Test
    void glideOrAvatarFlightMarkerExcludesNativeMovementRefresh() {
        assertTrue(CompanionMovementSpeedSyncSystem.shouldSkipManagedMovement(true, false));
        assertTrue(CompanionMovementSpeedSyncSystem.shouldSkipManagedMovement(false, true));
        assertFalse(CompanionMovementSpeedSyncSystem.shouldSkipManagedMovement(false, false));
    }

    @Test
    void fingerprintChangesForEveryManagedLifecycleInput() {
        UUID npcId = UUID.randomUUID();
        CompanionMovementSpeedSyncSystem.MovementSpeedFingerprint baseline =
                CompanionMovementSpeedSyncSystem.createFingerprint(
                        npcId, "Wolf_Default", Map.of("Saddle", "Plain"), 1.10, 3L, false, null);

        assertFalse(CompanionMovementSpeedSyncSystem.hasChanged(baseline, baseline));
        assertTrue(CompanionMovementSpeedSyncSystem.hasChanged(baseline,
                CompanionMovementSpeedSyncSystem.createFingerprint(
                        npcId, "Wolf_Armored", Map.of("Saddle", "Plain"), 1.10, 3L, false, null)));
        assertTrue(CompanionMovementSpeedSyncSystem.hasChanged(baseline,
                CompanionMovementSpeedSyncSystem.createFingerprint(
                        npcId, "Wolf_Default", Map.of("Saddle", "Armored"), 1.10, 3L, false, null)));
        assertTrue(CompanionMovementSpeedSyncSystem.hasChanged(baseline,
                CompanionMovementSpeedSyncSystem.createFingerprint(
                        npcId, "Wolf_Default", Map.of("Saddle", "Plain"), 1.15, 3L, false, null)));
        assertTrue(CompanionMovementSpeedSyncSystem.hasChanged(baseline,
                CompanionMovementSpeedSyncSystem.createFingerprint(
                        npcId, "Wolf_Default", Map.of("Saddle", "Plain"), 1.10, 4L, false, null)));
        assertTrue(CompanionMovementSpeedSyncSystem.hasChanged(baseline,
                CompanionMovementSpeedSyncSystem.createFingerprint(
                        npcId, "Wolf_Default", Map.of("Saddle", "Plain"), 1.10, 3L, true, UUID.randomUUID())));
        assertTrue(CompanionMovementSpeedSyncSystem.hasChanged(baseline,
                CompanionMovementSpeedSyncSystem.createFingerprint(
                        npcId, "Wolf_Default", Map.of("Saddle", "Plain"), 1.10, 3L, false, UUID.randomUUID())));
    }

    @Test
    void nativeMountRefreshUsesExactSpeedWhileUnmountedRefreshKeepsEffectQuantization() throws Exception {
        var resolved = new CompanionMovementSpeedResolver().resolve(
                new TwCompanionMovementConfig.ResolvedMovement("test:cow", 1.0, 0.5, 2.0, List.of()),
                Map.of(),
                1.024
        );

        assertEquals(1.024, CompanionMovementSpeedSyncSystem.selectAppliedMultiplier(true, resolved), 0.0000001);
        assertEquals(1.0, CompanionMovementSpeedSyncSystem.selectAppliedMultiplier(false, resolved), 0.0000001);
    }
}
