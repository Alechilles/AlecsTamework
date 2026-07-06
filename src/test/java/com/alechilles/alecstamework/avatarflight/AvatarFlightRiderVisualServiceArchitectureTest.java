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

    @Test
    void riderVisualServiceUsesModelAttachmentInsteadOfNativeMount() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("store.putComponent(ownerRef, visualType, marker(ownerUuid, null, false))"));
        assertTrue(source.contains("appendRiderAttachment("));
        assertTrue(source.contains("new ModelAttachment("));
        assertTrue(source.contains("savedModel.getModel()"));
        assertTrue(source.contains("savedModel.getTexture()"));
        assertFalse(source.contains("RIDER_PROXY_MODEL"));
        assertFalse(source.contains("RIDER_PROXY_TEXTURE"));
        assertTrue(source.contains("logRiderAttachment("));
        assertTrue(source.contains("logRiderAttachmentSkipped("));
        assertTrue(source.contains("riderModel=%s riderTexture=%s"));
        assertFalse(source.contains("config.getDebug().isLogControllerTicks()"));
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
}
