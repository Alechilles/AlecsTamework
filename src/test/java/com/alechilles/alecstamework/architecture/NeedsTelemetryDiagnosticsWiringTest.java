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
        assertTrue(content.contains("\"failureStage\""), "Seek failures must expose the failing resolution stage.");
        assertTrue(content.contains("\"sourceCandidate\""), "Seek failures must expose whether a resource source was found.");
        assertTrue(content.contains("\"containerStatus\""), "Consume failures must expose container failure status.");
        assertTrue(content.contains("\"matchingStackCountBucket\""), "Consume failures must expose matching stack buckets.");
        assertTrue(content.contains("\"scanRadiusBucket\""), "Consume failures must expose scan radius buckets.");
        assertTrue(content.contains("\"verticalScanBucket\""), "Consume failures must expose vertical scan buckets.");
        assertTrue(content.contains("\"nearestContainerDistanceBucket\""), "Consume failures must expose nearest container distance buckets.");
        assertTrue(content.contains("\"nearestAllowedContainerDistanceBucket\""), "Consume failures must expose nearest allowed-food distance buckets.");
        assertTrue(content.contains("\"scanSource\""), "Consume failures must expose container scan source.");
    }

    @Test
    void telemetryProjectAllowsDiagnosticBreakdownDetails() throws IOException {
        String content = Files.readString(TELEMETRY_PROJECT, StandardCharsets.UTF_8);

        assertTrue(content.contains("\"linked_respawn_failed\""), "Respawn failures must retain breakdown context.");
        assertTrue(content.contains("\"branch\""), "Respawn failures must expose dead/lost recovery branch.");
        assertTrue(content.contains("\"ui_page_open_failed\""), "UI open failures must retain page breakdown context.");
        assertTrue(content.contains("\"ui_page_build_failed\""), "UI build failures must retain page breakdown context.");
        assertTrue(content.contains("\"page\""), "UI failures must expose page names.");
        assertTrue(content.contains("\"config_editor_apply_failed\""), "Config editor failures must retain apply context.");
        assertTrue(content.contains("\"failureType\""), "Config editor failures must expose failure type.");
        assertTrue(content.contains("\"reload_config_override_errors\""), "Reload override errors must retain summary context.");
        assertTrue(content.contains("\"overrideErrorCountBucket\""), "Reload override errors must expose bucketed counts.");
        assertTrue(content.contains("\"persistence_write_failed\""), "Persistence failures must retain service context.");
        assertTrue(content.contains("\"service\""), "Persistence failures must expose service breakdowns.");
        assertTrue(content.contains("\"operation\""), "Diagnostic failures must expose operation breakdowns.");
    }
}
