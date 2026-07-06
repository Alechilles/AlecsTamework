package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderEquipmentArchitectureTest {
    private static final Path PACKET_SERVICE = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightEquipmentPacketService.java"
    );
    private static final Path EQUIPMENT_SYSTEM = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightEquipmentVisualSystem.java"
    );
    private static final Path TAMEWORK = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework", "Tamework.java"
    );

    @Test
    void packetServiceCanDescribeEquipmentChanges() throws Exception {
        String source = Files.readString(PACKET_SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("equipmentSignature("));
        assertTrue(source.contains("String.join"));
        assertTrue(source.contains("rightHandItemId"));
        assertTrue(source.contains("leftHandItemId"));
    }

    @Test
    void equipmentSystemDoesNotMirrorEquipmentToAttachmentBasedRiderEntity() throws Exception {
        String source = Files.readString(EQUIPMENT_SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("AvatarFlightRiderVisualComponent"));
        assertTrue(source.contains("riderVisualType"));
        assertFalse(source.contains("queueRiderEquipmentUpdate("));
        assertFalse(source.contains("AvatarFlightRiderVisualService.resolveRiderRef("));
        assertTrue(source.contains("getEquipmentResendIntervalMs()"));
        assertTrue(source.contains("commandBuffer.putComponent("));
    }

    @Test
    void tameworkRegistersEquipmentSystemWithRiderVisualType() throws Exception {
        String source = Files.readString(TAMEWORK, StandardCharsets.UTF_8);

        assertTrue(source.contains("new AvatarFlightEquipmentVisualSystem("));
        assertTrue(source.contains("avatarFlightRiderVisualComponentType"));
    }
}
