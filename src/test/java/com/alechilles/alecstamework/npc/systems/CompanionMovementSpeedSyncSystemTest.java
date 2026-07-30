package com.alechilles.alecstamework.npc.systems;

import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for companion movement-speed lifecycle change detection. */
class CompanionMovementSpeedSyncSystemTest {
    @Test
    void mountedRefreshUsesRecoveredRoleForNpcEffectAndCustomRiderProfile() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/CompanionMovementSpeedSyncSystem.java"));

        assertTrue(source.contains("applyResolvedMultiplier(npcRef, store, sourceRoleId, multiplier)"));
        assertTrue(source.contains("resolveMountedSourceRoleScopes(mount)"));
        assertTrue(source.contains("riderSettings.applyScaledSettings"));
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
