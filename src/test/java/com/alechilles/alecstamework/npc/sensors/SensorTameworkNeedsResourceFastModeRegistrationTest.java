package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SensorTameworkNeedsResourceFastModeRegistrationTest {
    @Test
    void builderIdIsRegisteredAndDocumentedInRegistrar() throws Exception {
        String registrar = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java"),
                StandardCharsets.UTF_8
        );
        String docs = Files.readString(
                Path.of("docs/Actions-Sensors-Components.md"),
                StandardCharsets.UTF_8
        );

        assertTrue(registrar.contains("BuilderSensorTameworkNeedsResourceFastMode"));
        assertTrue(docs.contains("TameworkNeedsResourceFastMode"));
    }
}
