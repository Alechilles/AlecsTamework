package com.alechilles.alecstamework.npc.systems;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for companion movement-speed lifecycle change detection. */
class CompanionMovementSpeedSyncSystemTest {
    @Test
    void lifecycleCompletionCommitsOnlyWhenNpcAndCurrentMountedRiderRefreshesSucceed() {
        assertTrue(CompanionMovementSpeedSyncSystem.isRefreshComplete(true, false, false, false));
        assertTrue(CompanionMovementSpeedSyncSystem.isRefreshComplete(true, true, true, true));
        assertFalse(CompanionMovementSpeedSyncSystem.isRefreshComplete(false, false, false, false));
        assertFalse(CompanionMovementSpeedSyncSystem.isRefreshComplete(true, true, false, false));
        assertFalse(CompanionMovementSpeedSyncSystem.isRefreshComplete(true, true, true, false));
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
}
