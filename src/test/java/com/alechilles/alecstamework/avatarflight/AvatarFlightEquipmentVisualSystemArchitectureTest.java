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
        assertFalse(source.contains("Arrays.fill(update.armorIds, BlockType.EMPTY_KEY)"));
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
        assertTrue(source.contains("refreshRiderVisualIfNeeded(ref, commandBuffer, settings)"));
    }

    @Test
    void visualSystemDoesNotSendHiddenOwnerEquipmentToSelfViewer() throws Exception {
        String source = Files.readString(SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("queueHiddenOwnerUpdate(ref, commandBuffer, visible, settings)"));
        assertTrue(source.contains("queueAllExceptSelf(ref, update, visible.visibleTo)"));
        assertTrue(source.contains("queueAllExceptSelf(ref, update, visible.newlyVisibleTo)"));
        assertTrue(source.contains("if (ref.equals(entry.getKey()))"));
        assertFalse(source.contains("queueIfEquipmentChanged("));
        assertTrue(source.contains("visualType"));
        assertFalse(source.contains("\"owner\""));
        assertFalse(source.contains("readSignature("));
        assertFalse(source.contains("writeSignature("));
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

    @Test
    void visualSystemRefreshesFakeRiderOnlyWhenArmorVisibilitySignatureChanges() throws Exception {
        String source = Files.readString(SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("AvatarFlightEquipmentAttachmentResolver.resolveSnapshot(ref, commandBuffer)"));
        assertTrue(source.contains("equipment.armorSignature().equals(visual.getEquipmentSignature())"));
        assertTrue(source.contains("modelService.savedModelCopy(ownerUuid)"));
        assertTrue(source.contains("riderVisualService.refresh(commandBuffer, ref, savedModel, equipment)"));
        assertTrue(source.contains("updated.setEquipmentSignature(equipment.armorSignature())"));
        assertTrue(source.contains("commandBuffer.putComponent(ref, visualType, updated)"));
    }
}
