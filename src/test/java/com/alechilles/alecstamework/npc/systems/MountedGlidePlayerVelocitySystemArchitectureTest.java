package com.alechilles.alecstamework.npc.systems;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MountedGlidePlayerVelocitySystemArchitectureTest {
    @Test
    void mountedGlideAppliesVelocityToRiderNotNpcMotionController() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystem.java"
        ));

        assertTrue(source.contains("NPCMountComponent"));
        assertTrue(source.contains("Velocity.getComponentType()"));
        assertTrue(source.contains("Velocity.addInstruction"));
        assertTrue(source.contains("ChangeVelocityType.Set"));
        assertTrue(source.contains("MountedGlidePhysics.update"));
    }
}
