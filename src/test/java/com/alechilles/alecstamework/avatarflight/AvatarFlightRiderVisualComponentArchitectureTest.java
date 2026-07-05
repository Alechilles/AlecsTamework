package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderVisualComponentArchitectureTest {
    @Test
    void modelServiceExposesDefensiveSavedModelCopy() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "avatarflight", "AvatarFlightModelService.java"
        ), StandardCharsets.UTF_8);

        assertTrue(source.contains("public Model savedModelCopy("));
        assertTrue(source.contains("return saved == null ? null : new Model(saved)"));
    }

    @Test
    void riderVisualComponentIsRegistered() throws Exception {
        String component = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "avatarflight", "AvatarFlightRiderVisualComponent.java"
        ), StandardCharsets.UTF_8);
        String plugin = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "Tamework.java"
        ), StandardCharsets.UTF_8);

        assertTrue(component.contains("BuilderCodec<AvatarFlightRiderVisualComponent> CODEC"));
        assertTrue(component.contains("private String riderEntityUuid"));
        assertTrue(component.contains("private String equipmentSignature"));
        assertTrue(plugin.contains("ComponentType<EntityStore, AvatarFlightRiderVisualComponent> avatarFlightRiderVisualComponentType"));
        assertTrue(plugin.contains("\"TameworkAvatarFlightRiderVisual\""));
    }
}
