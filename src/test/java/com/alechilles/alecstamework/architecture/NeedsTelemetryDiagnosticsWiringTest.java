package com.alechilles.alecstamework.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Guards command, default-config, and telemetry descriptor wiring for needs telemetry diagnostics.
 */
class NeedsTelemetryDiagnosticsWiringTest {
    private static final Path COMMAND_ROOT = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "commands", "TameworkCommandRoot.java"
    );
    private static final Path DEFAULT_DEBUG_CONFIG = Paths.get(
            "src", "main", "resources", "Server", "Tamework", "Debug", "TwDebugDefault.json"
    );
    private static final Path TELEMETRY_PROJECT = Paths.get(
            "src", "main", "resources", "telemetry", "project.json"
    );

    @Test
    void rootCommandRegistersNeedsTelemetryDebugCommand() throws IOException {
        String content = Files.readString(COMMAND_ROOT, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("addSubCommand(new TameworkDebugNeedsTelemetryCommand());"),
                "/tw must register the debugneedstelemetry command."
        );
    }

    @Test
    void defaultDebugConfigEnablesNeedsTelemetryByDefault() throws IOException {
        String content = Files.readString(DEFAULT_DEBUG_CONFIG, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("\"NeedsTelemetry\": true"),
                "Needs telemetry diagnostics should be on by default while global telemetry remains the outer opt-in."
        );
    }

    @Test
    void telemetryProjectAllowsNeedsContextDetails() throws IOException {
        String content = Files.readString(TELEMETRY_PROJECT, StandardCharsets.UTF_8);

        assertTrue(content.contains("\"needs_seek_failed\""), "Seek failures must be declared for error details.");
        assertTrue(content.contains("\"needs_consume_failed\""), "Consume failures must be declared for error details.");
        assertTrue(content.contains("\"reason\""), "Needs telemetry must retain low-cardinality failure reasons.");
        assertTrue(content.contains("\"resource\""), "Needs telemetry must retain resource breakdowns.");
        assertTrue(content.contains("\"needBucket\""), "Needs seek telemetry must retain bucketed need context.");
    }
}
