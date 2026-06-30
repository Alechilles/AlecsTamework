package com.alechilles.alecstamework.npc.systems;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountedGlideInputCaptureArchitectureTest {

    @Test
    void mountedGlideInputStackDoesNotDependOnLegacyRideComponents() throws IOException {
        String source = readGlideInputStack();

        assertFalse(source.contains("TameworkRideMountComponent"));
        assertFalse(source.contains("TameworkRideRiderComponent"));
    }

    @Test
    void mountedGlideInputStackAvoidsUnsafePlayerComponentAccess() throws IOException {
        String source = readGlideInputStack();

        assertFalse(source.contains("PlayerRef.getComponent("));
        assertFalse(source.contains("getComponent(Player.getComponentType())"));
    }

    @Test
    void mountedGlideInputStackDoesNotConsumeDropOrMouseButtons() throws IOException {
        String source = readGlideInputStack();
        String packetHandler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedGlidePacketHandler.java"
        ));

        assertFalse(source.contains("DropItem"));
        assertFalse(source.contains("DropItemStack"));
        assertFalse(source.contains("DropItemEvent"));
        assertTrue(packetHandler.contains("delegate(mouseInteractionDelegate, packet)"));
    }

    private static String readGlideInputStack() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java"
        )) + Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java"
        )) + Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedGlidePacketHandler.java"
        ));
    }
}
