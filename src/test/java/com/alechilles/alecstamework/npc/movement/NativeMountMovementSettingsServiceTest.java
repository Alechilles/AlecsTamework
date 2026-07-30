package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.protocol.MovementSettings;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertEquals(true, method.contains("return originalRoleId"));
        assertEquals(true, method.contains("return null;"));
        assertEquals(true, method.contains("return null;\n        }\n        NPCEntity"));
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
        assertEquals(true, method.contains("world.getEntityRef(ownerUuid)"));
        assertEquals(false, method.contains("owner.getReference()"));
    }
}
