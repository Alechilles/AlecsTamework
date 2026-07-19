package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards avatar-flight trails against regressing to interaction-chain render state. */
class AvatarFlightTrailInteractionSafetyTest {
    private static final Path SERVICE = Path.of(
            "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightTrailService.java");

    @Test
    void trailsUseSynchronizedModelStateInsteadOfInteractionChains() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("ModelComponent.getComponentType()"),
                "model-owned trails must be synchronized through ModelComponent");
        assertTrue(source.contains("commandBuffer.putComponent"),
                "runtime model changes must use the ECS command buffer");
        assertTrue(source.contains("AvatarFlightModelTrailComposer.withTrails"),
                "trail state changes must preserve unmanaged model trails");
        assertFalse(source.contains("initChain("),
                "interaction-owned trails do not reliably clear from transformed models");
        assertFalse(source.contains("queueExecuteChain("),
                "interaction-owned trails do not reliably appear on the transformed model");
        assertFalse(source.contains("cancelChains("),
                "trail cleanup must replace model state instead of cancelling a remote chain");
    }
}
