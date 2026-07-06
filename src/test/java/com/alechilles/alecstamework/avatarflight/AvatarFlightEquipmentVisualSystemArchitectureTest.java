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
    void visualSystemThrottlesSelfEquipmentPacketsWithVisualMarker() throws Exception {
        String source = Files.readString(SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("queueSelfIfChanged("));
        assertTrue(source.contains("equipmentSignature(currentUpdate)"));
        assertTrue(source.contains("signature.equals(riderVisual.getEquipmentSignature())"));
        assertTrue(source.contains("updated.setEquipmentSignature(signature)"));
        assertTrue(source.contains("queueSelf(ref, hiddenUpdate"));
    }

    @Test
    void visualSystemKeepsNormalOwnerPacketsOffOtherViewerQueue() throws Exception {
        String source = Files.readString(SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("private static void queueOthers("));
        assertTrue(source.contains("sameEntity(ref, entry.getKey())"));
        assertTrue(source.contains("continue;"));
        assertTrue(source.contains("private static boolean sameEntity("));
    }
}
