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
    private static final Path ATTACHMENT_RESOLVER = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightEquipmentAttachmentResolver.java"
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
    void equipmentSystemDoesNotQueueFakeRiderEquipmentPackets() throws Exception {
        String source = Files.readString(EQUIPMENT_SYSTEM, StandardCharsets.UTF_8);

        assertFalse(source.contains("queueRiderEquipmentUpdate("));
        assertFalse(source.contains("AvatarFlightRiderVisualService.resolveRiderRef("));
        assertFalse(source.contains("AvatarFlightRiderVisualComponent"));
        assertFalse(source.contains("riderVisualType"));
        assertFalse(source.contains("getEquipmentResendIntervalMs()"));
        assertFalse(source.contains("commandBuffer.putComponent("));
    }

    @Test
    void equipmentAttachmentResolverUsesArmorOnlyForCosmeticHiding() throws Exception {
        String source = Files.readString(ATTACHMENT_RESOLVER, StandardCharsets.UTF_8);

        assertTrue(source.contains("createCurrentEquipmentUpdate(ref, accessor)"));
        assertTrue(source.contains("resolveSnapshot("));
        assertTrue(source.contains("EquipmentSnapshot"));
        assertFalse(source.contains("update.rightHandItemId"));
        assertFalse(source.contains("update.leftHandItemId"));
        assertTrue(source.contains("update.armorIds"));
        assertTrue(source.contains("EnumSet.noneOf(Cosmetic.class)"));
        assertTrue(source.contains("collectHiddenCosmetics("));
        assertTrue(source.contains("item.getArmor()"));
        assertTrue(source.contains("item.getArmor().toPacket()"));
        assertTrue(source.contains("armor.cosmeticsToHide"));
        assertTrue(source.contains("hiddenCosmetics.add(cosmetic)"));
        assertTrue(source.contains("BlockType.EMPTY_KEY"));
        assertTrue(source.contains("Item.getAssetMap().getAsset(itemId)"));
        assertTrue(source.contains("NO_ATTACHMENTS"));
        assertFalse(source.contains("item.getModel()"));
        assertFalse(source.contains("item.getTexture()"));
        assertFalse(source.contains("new ModelAttachment(model, texture, null, null, 1.0)"));
    }

    @Test
    void tameworkRegistersEquipmentSystemWithoutRiderVisualType() throws Exception {
        String source = Files.readString(TAMEWORK, StandardCharsets.UTF_8);
        int constructor = source.indexOf("new AvatarFlightEquipmentVisualSystem(");

        assertTrue(source.contains("new AvatarFlightEquipmentVisualSystem("));
        assertTrue(constructor >= 0);
        assertFalse(source.substring(constructor, source.indexOf(");", constructor))
                .contains("avatarFlightRiderVisualComponentType"));
    }
}
