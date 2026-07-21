package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightGroundMovementServiceArchitectureTest {
    private static final Path SERVICE = Path.of(
            "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightGroundMovementService.java");
    private static final Path MOVEMENT_SYSTEM = Path.of(
            "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java");

    @Test
    void groundedOverridePreservesAndRestoresTheExistingBaseSpeed() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("flight.captureGroundedBaseSpeed(settings.baseSpeed)"));
        assertTrue(source.contains("settings.baseSpeed = target"));
        assertTrue(source.contains("flight.getOriginalGroundedBaseSpeed()"));
        assertTrue(source.contains("flight.clearGroundedBaseSpeed()"));
    }

    @Test
    void tickingPathUsesCommandBufferAndExcludesAirborneAndFluidMovement() throws Exception {
        String service = Files.readString(SERVICE, StandardCharsets.UTF_8);
        String movement = Files.readString(MOVEMENT_SYSTEM, StandardCharsets.UTF_8);

        assertTrue(service.contains("commandBuffer.putComponent(ref, MovementManager.getComponentType(), updated)"));
        assertTrue(movement.contains("output.mode() == AvatarFlightMode.GROUNDED"));
        assertTrue(movement.contains("controllerInput.onGround()"));
        assertTrue(movement.contains("!controllerInput.inFluid()"));
    }
}
