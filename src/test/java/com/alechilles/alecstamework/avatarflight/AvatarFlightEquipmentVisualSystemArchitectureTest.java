package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightEquipmentVisualSystemArchitectureTest {
    private static final Path SYSTEM = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightEquipmentVisualSystem.java"
    );
    private static final Path SERVICE = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightEquipmentPacketService.java"
    );

    @Test
    void equipmentServiceBlanksOwnerArmorAndHandsWithoutMutatingInventory() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("createHiddenOwnerEquipmentUpdate("));
        assertTrue(source.contains("update.rightHandItemId = BlockType.EMPTY_KEY"));
        assertTrue(source.contains("update.leftHandItemId = BlockType.EMPTY_KEY"));
        assertTrue(source.contains("Arrays.fill(update.armorIds, \"\")"));
        assertFalse(source.contains("removeItem"));
        assertFalse(source.contains("setItem"));
    }

    @Test
    void visualSystemUsesConfigurablePacketService() throws Exception {
        String source = Files.readString(SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("TwAvatarFlightConfig.resolve(flight.getConfigId())"));
        assertTrue(source.contains("config.getRiderVisual()"));
        assertTrue(source.contains("settings.isHideOwnerEquipment()"));
        assertTrue(source.contains("AvatarFlightEquipmentPacketService.createHiddenOwnerEquipmentUpdate("));
        assertTrue(source.contains("AvatarFlightEquipmentPacketService.createCurrentEquipmentUpdate("));
    }

    @Test
    void visualSystemQueuesHiddenOwnerEquipmentOnlyWhenSignatureRequiresIt() throws Exception {
        String source = Files.readString(SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("queueHiddenOwnerUpdate(ref, commandBuffer, visible, settings, riderVisual)"));
        assertTrue(source.contains("queueIfEquipmentChanged("));
        assertTrue(source.contains("\"owner\""));
        assertTrue(source.contains("readSignature("));
        assertTrue(source.contains("writeSignature("));
        assertTrue(source.contains("queueAll(ref, update, visible.visibleTo)"));
        assertTrue(source.contains("queueAll(ref, update, visible.newlyVisibleTo)"));
        assertFalse(source.contains("queueSelf(ref, hiddenUpdate"));
    }

    @Test
    void visualSystemDoesNotQueueFakeRiderEquipmentPackets() throws Exception {
        String source = Files.readString(SYSTEM, StandardCharsets.UTF_8);

        assertFalse(source.contains("queueRiderEquipmentUpdate("));
        assertFalse(source.contains("queueOthers("));
        assertFalse(source.contains("sameEntity("));
        assertFalse(source.contains("AvatarFlightRiderVisualService.resolveRiderRef("));
    }
}
