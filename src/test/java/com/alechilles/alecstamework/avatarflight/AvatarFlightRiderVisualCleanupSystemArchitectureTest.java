package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderVisualCleanupSystemArchitectureTest {
    private static final Path SYSTEM = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightRiderVisualCleanupSystem.java"
    );
    private static final Path TAMEWORK = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework", "Tamework.java"
    );

    @Test
    void cleanupUsesTickSystemAndCommandBufferWrites() throws Exception {
        String source = Files.readString(SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("extends EntityTickingSystem<EntityStore>"));
        assertTrue(source.contains("CommandBuffer<EntityStore>"));
        assertTrue(source.contains("commandBuffer.tryRemoveEntity("));
        assertTrue(source.contains("commandBuffer.tryRemoveComponent("));
        assertTrue(source.contains("Query.and(visualType)"));
    }

    @Test
    void cleanupHandlesBothOwnerAndRiderMarkers() throws Exception {
        String source = Files.readString(SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("visual.isRiderEntity()"));
        assertTrue(source.contains("cleanupRiderMarker("));
        assertTrue(source.contains("cleanupOwnerMarker("));
        assertTrue(source.contains("AvatarFlightRiderVisualService.resolveRiderRef("));
        assertTrue(source.contains("resolveOwnerRef("));
    }

    @Test
    void tameworkRegistersCleanupSystemWithFlightAndVisualTypes() throws Exception {
        String source = Files.readString(TAMEWORK, StandardCharsets.UTF_8);

        assertTrue(source.contains("new AvatarFlightRiderVisualCleanupSystem("));
        assertTrue(source.contains("avatarFlightRiderVisualComponentType"));
        assertTrue(source.contains("avatarFlightComponentType"));
    }
}
