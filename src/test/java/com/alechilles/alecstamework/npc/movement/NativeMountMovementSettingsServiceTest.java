package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.protocol.MovementSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Tests the isolated native-mount rider movement-settings scaling rule. */
class NativeMountMovementSettingsServiceTest {

    @Test
    void copiedSettingsScaleOnlyBaseSpeedOnce() {
        MovementSettings source = new MovementSettings();
        source.baseSpeed = 6.0F;
        source.acceleration = 0.35F;
        source.jumpForce = 13.0F;
        source.canFly = true;

        MovementSettings scaled = NativeMountMovementSettingsService.copyWithScaledBaseSpeed(source, 1.25);

        assertNotSame(source, scaled);
        assertEquals(6.0F, source.baseSpeed);
        assertEquals(7.5F, scaled.baseSpeed);
        assertEquals(source.acceleration, scaled.acceleration);
        assertEquals(source.jumpForce, scaled.jumpForce);
        assertEquals(source.canFly, scaled.canFly);
    }

    @Test
    void invalidInputsUseNeutralMultiplier() {
        MovementSettings source = new MovementSettings();
        source.baseSpeed = 6.0F;

        assertEquals(6.0F,
                NativeMountMovementSettingsService.copyWithScaledBaseSpeed(source, Double.NaN).baseSpeed);
        assertEquals(6.0F,
                NativeMountMovementSettingsService.copyWithScaledBaseSpeed(source, 0.0).baseSpeed);
        assertEquals(0.0F,
                NativeMountMovementSettingsService.copyWithScaledBaseSpeed(null, 1.25).baseSpeed);
    }

    @Test
    void mountedRoleRecoveryNeverFallsBackToVisibleEmptyRole() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/movement/NativeMountMovementSettingsService.java"
        ));
        int methodStart = source.indexOf("static String resolveManagedRoleId");
        int methodEnd = source.indexOf("static Ref<EntityStore> resolveMountedRiderRef");
        String method = source.substring(methodStart, methodEnd);

        assertEquals(true, method.contains("if (mount != null)"));
        assertEquals(true, method.contains("resolveMountedRoleId("));
        assertEquals(true, method.contains("resolveManagedRoleId(false, null"));
    }

    @Test
    void mountedRiderResolutionUsesStableUuidInTheActiveWorldStore() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/movement/NativeMountMovementSettingsService.java"
        ));
        int methodStart = source.indexOf("static Ref<EntityStore> resolveMountedRiderRef");
        int methodEnd = source.indexOf("static MovementSettings copyWithScaledBaseSpeed");
        String method = source.substring(methodStart, methodEnd);

        assertEquals(true, method.contains("owner.getUuid()"));
        assertEquals(true, method.contains("resolveActiveRider("));
        assertEquals(false, method.contains("owner.getReference()"));
    }

    @Test
    void unmountedRoleRecoveryReturnsCurrentRole() {
        assertEquals("Wolf_Default", NativeMountMovementSettingsService.resolveManagedRoleId(
                false, null, "Wolf_Default"));
    }

    @Test
    void mappedMountedRoleRecoveryReturnsOriginalRole() {
        RecordingRoleNames names = new RecordingRoleNames("Horse_Default");

        assertEquals("Horse_Default", NativeMountMovementSettingsService.resolveMountedRoleId(42, names));
        assertEquals(42, names.requestedIndex);
    }

    @Test
    void unmappableMountedRoleRecoveryReturnsNull() {
        RecordingRoleNames names = new RecordingRoleNames(null);

        assertEquals(null, NativeMountMovementSettingsService.resolveMountedRoleId(99, names));
        assertEquals(99, names.requestedIndex);
    }

    @Test
    void riderResolutionReturnsTheActiveWorldEntity() {
        UUID riderId = UUID.randomUUID();
        RecordingRiderLookup lookup = new RecordingRiderLookup("active-store-rider", true);
        String resolved = NativeMountMovementSettingsService.resolveActiveRider(riderId, lookup);

        assertEquals("active-store-rider", resolved);
        assertEquals(riderId, lookup.requestedUuid);
        assertEquals(1, lookup.playerValidationCalls);
    }

    @Test
    void riderResolutionRejectsMissingActiveWorldEntity() {
        UUID riderId = UUID.randomUUID();
        RecordingRiderLookup lookup = new RecordingRiderLookup(null, true);

        assertEquals(null, NativeMountMovementSettingsService.resolveActiveRider(riderId, lookup));
        assertEquals(riderId, lookup.requestedUuid);
        assertEquals(0, lookup.playerValidationCalls);
    }

    @Test
    void riderResolutionRejectsEntityWithoutActivePlayer() {
        UUID riderId = UUID.randomUUID();
        RecordingRiderLookup lookup = new RecordingRiderLookup("wrong-store-or-not-player", false);

        assertEquals(null, NativeMountMovementSettingsService.resolveActiveRider(riderId, lookup));
        assertEquals(riderId, lookup.requestedUuid);
        assertEquals(1, lookup.playerValidationCalls);
    }

    private static final class RecordingRoleNames implements NativeMountMovementSettingsService.RoleNameLookup {
        private final String name;
        private int requestedIndex = Integer.MIN_VALUE;

        private RecordingRoleNames(String name) {
            this.name = name;
        }

        @Override
        public String getName(int originalRoleIndex) {
            requestedIndex = originalRoleIndex;
            return name;
        }
    }

    private static final class RecordingRiderLookup
            implements NativeMountMovementSettingsService.ActiveRiderLookup<String> {
        private final String result;
        private final boolean activePlayer;
        private UUID requestedUuid;
        private int playerValidationCalls;

        private RecordingRiderLookup(String result, boolean activePlayer) {
            this.result = result;
            this.activePlayer = activePlayer;
        }

        @Override
        public String findInActiveWorld(UUID riderUuid) {
            requestedUuid = riderUuid;
            return result;
        }

        @Override
        public boolean isActivePlayer(String rider) {
            playerValidationCalls++;
            return activePlayer;
        }
    }
}
