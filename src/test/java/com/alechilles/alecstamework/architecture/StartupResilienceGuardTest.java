package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces startup resilience guardrails for optional dependencies.
 */
class StartupResilienceGuardTest {
    private static final Path TAMEWORK_PATH = Paths.get(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "Tamework.java"
    );
    private static final Path EVENT_REGISTRATION_SUPPORT_PATH = Paths.get(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "lifecycle",
            "TameworkEventRegistrationSupport.java"
    );

    @Test
    void revivableDropSuppressionSystemRegistrationIsOptional() throws IOException {
        String content = Files.readString(TAMEWORK_PATH, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("registerOptionalCommandLinkedRevivableDropSuppressionSystem();"),
                "Tamework setup must call optional revivable-drop-suppression registration wrapper."
        );
        assertTrue(
                content.contains("private void registerOptionalCommandLinkedRevivableDropSuppressionSystem()"),
                "Tamework must define optional revivable-drop-suppression registration helper."
        );
        assertTrue(
                content.contains("Skipping command-linked revivable drop suppression system"),
                "Optional revivable-drop-suppression setup must log a warning when skipped."
        );
    }

    @Test
    void despawnDiagnosticsHandlesMissingSpawnPluginGracefully() throws IOException {
        String content = Files.readString(TAMEWORK_PATH, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("resolveOptionalSpawnMarkerReferenceComponentType()"),
                "Tamework setup must resolve SpawnMarkerReference component type through optional helper."
        );
        assertTrue(
                content.contains("resolveOptionalSpawnBeaconReferenceComponentType()"),
                "Tamework setup must resolve SpawnBeaconReference component type through optional helper."
        );
        assertTrue(
                content.contains("SpawnMarkerReference component type is unavailable"),
                "Missing spawn marker component type should warn and continue startup."
        );
        assertTrue(
                content.contains("SpawnBeaconReference component type is unavailable"),
                "Missing spawn beacon component type should warn and continue startup."
        );
    }

    @Test
    void setupGlobalEventListenersHandleShutdownRegistryGracefully() throws IOException {
        String tameworkContent = Files.readString(TAMEWORK_PATH, StandardCharsets.UTF_8);
        String helperContent = Files.readString(EVENT_REGISTRATION_SUPPORT_PATH, StandardCharsets.UTF_8);

        assertTrue(
                tameworkContent.contains("TameworkEventRegistrationSupport.registerGlobal("),
                "Setup-time global listeners should use the startup-safe registration helper."
        );
        assertTrue(
                helperContent.contains("EventRegistry is shutdown!"),
                "The helper must recognize the Hytale shutdown-registry startup failure."
        );
        assertTrue(
                helperContent.contains("throw ex;"),
                "The helper should only swallow the known shutdown-registry failure."
        );
    }
}
