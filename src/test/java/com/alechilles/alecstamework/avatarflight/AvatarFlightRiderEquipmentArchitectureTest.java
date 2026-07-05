package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void equipmentSystemMirrorsRealOwnerEquipmentToRiderEntity() throws Exception {
        String source = Files.readString(EQUIPMENT_SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("AvatarFlightRiderVisualComponent"));
        assertTrue(source.contains("riderVisualType"));
        assertTrue(source.contains("queueRiderEquipmentUpdate("));
        assertTrue(source.contains("AvatarFlightRiderVisualService.resolveRiderRef("));
        assertTrue(source.contains("getEquipmentResendIntervalMs()"));
        assertTrue(source.contains("commandBuffer.putComponent("));
        assertTrue(source.contains("createCurrentEquipmentUpdate(ref, accessor)"));
    }

    @Test
    void tameworkRegistersEquipmentSystemWithRiderVisualType() throws Exception {
        String source = Files.readString(TAMEWORK, StandardCharsets.UTF_8);

        assertTrue(source.contains("new AvatarFlightEquipmentVisualSystem("));
        assertTrue(source.contains("avatarFlightRiderVisualComponentType"));
    }
}
