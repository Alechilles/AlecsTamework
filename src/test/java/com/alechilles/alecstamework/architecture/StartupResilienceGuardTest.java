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
    void commandItemAssetSetupHandlesMissingVector3dGracefully() throws IOException {
        String content = Files.readString(TAMEWORK_PATH, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("private static boolean isMissingVector3d"),
                "Tamework must keep Vector3d runtime-availability guard helper."
        );
        assertTrue(
                content.contains("Skipping command-item asset registration because Vector3d is unavailable"),
                "Command-item registration must degrade gracefully when Vector3d is absent."
        );
        assertTrue(
                content.contains("Skipping command-item asset loading because Vector3d is unavailable"),
                "Command-item loading must degrade gracefully when Vector3d is absent."
        );
    }
}
