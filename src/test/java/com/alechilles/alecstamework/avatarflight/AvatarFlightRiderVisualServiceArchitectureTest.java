package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderVisualServiceArchitectureTest {
    private static final Path SERVICE = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightRiderVisualService.java"
    );
    private static final Path ACTIVATOR = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightActivator.java"
    );
    private static final Path SKIN_RESOLVER = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightPlayerSkinAttachmentResolver.java"
    );

    @Test
    void riderVisualServiceUsesModelAttachmentInsteadOfNativeMount() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("store.putComponent(ownerRef, visualType, marker(ownerUuid, null, false, "
                + "equipment.armorSignature()))"));
        assertTrue(source.contains("appendRiderAttachment("));
        assertTrue(source.contains("new ModelAttachment("));
        assertTrue(source.contains("savedModel.getModel()"));
        assertTrue(source.contains("savedModel.getTexture()"));
        assertTrue(source.contains("savedModel.getAttachments()"));
        assertTrue(source.contains("PlayerSkinComponent"));
        assertTrue(source.contains("AvatarFlightPlayerSkinAttachmentResolver.resolve("));
        assertTrue(source.contains("AvatarFlightEquipmentAttachmentResolver.resolveSnapshot(ownerRef, store)"));
        assertTrue(source.contains("equipment.hiddenCosmetics()"));
        assertTrue(source.contains("equipment.armorSignature()"));
        assertTrue(source.contains("equipment.hiddenCosmetics()"));
        assertTrue(source.contains("appearanceAttachments"));
        assertTrue(source.contains("equipment.attachments()"));
        assertTrue(source.contains("modelWithoutRiderAttachments("));
        assertTrue(source.contains("riderSafeAttachments("));
        assertTrue(source.contains("AvatarFlightRiderModelVariantService.isGeneratedVariant(model)"));
        assertFalse(source.contains("PLAYER_ARMOR_ANCHOR_MODEL"));
        assertFalse(source.contains("armorAnchorAttachment("));
        assertTrue(source.contains("System.arraycopy(appearanceAttachments, 0, attachments, 1"));
        assertTrue(source.contains("System.arraycopy("));
        assertTrue(source.contains("System.arraycopy(riderAttachments, 0, attachments, baseCount"));
        assertFalse(source.contains("RIDER_PROXY_MODEL"));
        assertFalse(source.contains("RIDER_PROXY_TEXTURE"));
        assertTrue(source.contains("logRiderAttachment("));
        assertTrue(source.contains("logRiderAttachmentSkipped("));
        assertTrue(source.contains("PLAYER_MOUNT_ANCHOR_MODEL"));
        assertTrue(source.contains("riderAttachmentModel(model)"));
        assertFalse(source.contains("Collections.emptySet()"));
        assertTrue(source.contains("attachmentModel=%s riderTexture=%s"));
        assertTrue(source.contains("riderAppearanceAttachmentCount=%s"));
        assertTrue(source.contains("riderSkinAttachmentCount=%s"));
        assertTrue(source.contains("riderEquipmentAttachmentCount=%s"));
        assertTrue(source.contains("riderHiddenCosmeticCount=%s"));
        assertFalse(source.contains("config.getDebug().isLogControllerTicks()"));
        assertTrue(source.contains("Arrays.copyOf("));
        assertTrue(source.contains("store.putComponent(ownerRef, ModelComponent.getComponentType()"));
        assertFalse(source.contains("new MountedComponent("));
        assertFalse(source.contains("MountController.BlockMount"));
        assertFalse(source.contains("AddReason.SPAWN"));
        assertFalse(source.contains("new NetworkId("));
        assertTrue(source.contains("RemoveReason.REMOVE"));
        assertFalse(source.contains("PlayerRef.getComponent(Player"));
        assertTrue(source.contains("refresh(@Nonnull CommandBuffer<EntityStore> commandBuffer"));
        assertTrue(source.contains("commandBuffer.putComponent(ownerRef, ModelComponent.getComponentType()"));
    }

    @Test
    void playerRiderAttachmentModelUsesMountAnchorPiece() throws Exception {
        Path riderModel = Path.of(
                "src", "main", "resources", "Common", "Tamework", "AvatarFlight",
                "Rider", "Player_MountAnchor.blockymodel"
        );
        String json = Files.readString(riderModel, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertTrue(json.contains("\"name\": \"MountAnchor\""));
        assertTrue(json.contains("\"isPiece\": true"));
        assertTrue(json.contains("\"name\": \"TameworkRider_Origin\""));
        assertTrue(json.contains("\"id\": \"tw_rider_146\""));
        assertTrue(json.contains("\"name\": \"R-Attachment\""));
        assertTrue(json.contains("\"name\": \"L-Attachment\""));
        assertTrue(json.contains("\"name\": \"Head\""),
                "the fake rider should keep the standard head node so player look tracking can still affect it");
        assertFalse(json.matches("(?s).*\"id\"\\s*:\\s*\"[0-9]+\".*"),
                "fake rider node ids must be namespaced so player/flight animation tracks cannot target them by id");
        assertFalse(json.contains("\"name\": \"Origin\""));
        assertFalse(json.contains("\"name\": \"Pelvis\""));
        assertFalse(json.contains("\"name\": \"Chest\""));
        assertFalse(json.contains("\"name\": \"L-Thigh\""));
        assertFalse(json.contains("\"name\": \"R-Thigh\""));
        int pelvis = json.indexOf("\"name\": \"TameworkRider_Pelvis\"");
        int pelvisOrientation = json.indexOf("\"orientation\"", pelvis);
        assertTrue(pelvis >= 0);
        assertTrue(json.indexOf("\"y\": 0", pelvis) < pelvisOrientation);
        assertTrue(json.contains("\"x\": -0.707107"));
        assertTrue(json.contains("\"x\": 0.707107"));
        assertTrue(json.contains("\"x\": -0.461749"));
        assertTrue(json.contains("\"x\": -0.300706"));
        assertTrue(json.split("\"isPiece\": true", -1).length - 1 == 1);
    }

    @Test
    void activatorStartsAndStopsRiderVisualsAroundModelSwap() throws Exception {
        String source = Files.readString(ACTIVATOR, StandardCharsets.UTF_8);

        assertTrue(source.contains("riderVisualService.spawn("));
        assertTrue(source.contains("riderVisualService.remove("));
        assertTrue(source.indexOf("modelService.apply(") < source.indexOf("riderVisualService.spawn("));
        assertTrue(source.indexOf("riderVisualService.remove(") < source.indexOf("modelService.restore("));
    }

    @Test
    void playerSkinAttachmentResolverExpandsCosmeticSlots() throws Exception {
        String source = Files.readString(SKIN_RESOLVER, StandardCharsets.UTF_8);

        assertTrue(source.contains("registry.getHaircuts()"));
        assertTrue(source.contains("registry.getPants()"));
        assertTrue(source.contains("registry.getOverpants()"));
        assertTrue(source.contains("registry.getUndertops()"));
        assertTrue(source.contains("registry.getOvertops()"));
        assertTrue(source.contains("registry.getShoes()"));
        assertTrue(source.contains("registry.getGloves()"));
        assertTrue(source.contains("registry.getHeadAccessories()"));
        assertTrue(source.contains("registry.getFaceAccessories()"));
        assertTrue(source.contains("registry.getEarAccessories()"));
        assertTrue(source.contains("registry.getCapes()"));
        assertTrue(source.contains("Set<Cosmetic> hiddenCosmetics"));
        assertTrue(source.contains("appendUnlessHidden("));
        assertTrue(source.contains("hiddenCosmetics.contains(Cosmetic.Haircut)"));
        assertTrue(source.contains("hiddenCosmetics.contains(Cosmetic.HeadAccessory) ? null : skin.headAccessory"));
        assertTrue(source.contains("Cosmetic.Ear"));
        assertTrue(source.contains("Cosmetic.FacialHair"));
        assertTrue(source.contains("Cosmetic.Undertop"));
        assertTrue(source.contains("Cosmetic.Overtop"));
        assertTrue(source.contains("Cosmetic.Pants"));
        assertTrue(source.contains("Cosmetic.Overpants"));
        assertTrue(source.contains("Cosmetic.Shoes"));
        assertTrue(source.contains("Cosmetic.Gloves"));
        assertTrue(source.contains("Cosmetic.Cape"));
        assertTrue(source.contains("Cosmetic.HeadAccessory"));
        assertTrue(source.contains("Cosmetic.FaceAccessory"));
        assertTrue(source.contains("Cosmetic.EarAccessory"));
        assertTrue(source.contains("PlayerSkinPartTexture"));
        assertTrue(source.contains("part.getGreyscaleTexture()"));
        assertTrue(source.contains("part.getGradientSet()"));
        assertTrue(source.contains("modelOverride == null"));
        assertTrue(source.contains("AvatarFlightRiderModelVariantService.resolveForRider(model)"));
        assertTrue(source.contains("variant.getTextures()"));
        assertTrue(source.contains("new ModelAttachment("));
        assertTrue(source.contains("Generic\" + hairPart.getHairType()"));
    }
}
