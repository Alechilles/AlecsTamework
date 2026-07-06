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
    private static final Path RIDER_PROXY = Path.of(
            "src", "main", "resources", "Common", "NPC", "Tamework",
            "AvatarFlight", "RiderProxy.blockymodel"
    );

    @Test
    void riderVisualServiceUsesModelAttachmentInsteadOfNativeMount() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("store.putComponent(ownerRef, visualType, marker(ownerUuid, null, false))"));
        assertTrue(source.contains("appendRiderAttachment("));
        assertTrue(source.contains("new ModelAttachment("));
        assertTrue(source.contains("RIDER_PROXY_MODEL"));
        assertTrue(source.contains("RIDER_PROXY_TEXTURE"));
        assertFalse(source.contains("savedModel.getModel()"));
        assertFalse(source.contains("savedModel.getTexture()"));
        assertTrue(source.contains("Arrays.copyOf("));
        assertTrue(source.contains("store.putComponent(ownerRef, ModelComponent.getComponentType()"));
        assertFalse(source.contains("new MountedComponent("));
        assertFalse(source.contains("MountController.BlockMount"));
        assertFalse(source.contains("AddReason.SPAWN"));
        assertFalse(source.contains("new NetworkId("));
        assertTrue(source.contains("RemoveReason.REMOVE"));
        assertFalse(source.contains("PlayerRef.getComponent(Player"));
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
    void riderProxyModelTargetsNordicDrakeTorsoPiece() throws Exception {
        String source = Files.readString(RIDER_PROXY, StandardCharsets.UTF_8);

        assertTrue(source.contains("\"name\": \"Chest\""));
        assertTrue(source.contains("\"isPiece\": true"));
        assertTrue(source.contains("\"name\": \"RiderProxyAnchor\""));
        assertTrue(source.contains("\"name\": \"RiderProxyTorso\""));
        assertTrue(source.contains("\"name\": \"RiderProxyHead\""));
    }
}
