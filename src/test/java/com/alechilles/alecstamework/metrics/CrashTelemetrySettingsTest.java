package com.alechilles.alecstamework.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashTelemetrySettingsTest {

    @TempDir
    Path tempDir;

    @Test
    void createsEnabledOnlyTemplateWithFixedEndpointDefaults() throws Exception {
        Path file = tempDir.resolve(CrashTelemetrySettings.FILE_NAME);

        CrashTelemetrySettings settings = CrashTelemetrySettings.load(file, null);

        assertTrue(settings.enabled());
        assertTrue(settings.breadcrumbsEnabled());
        assertEquals(40, settings.breadcrumbsCapacity());
        assertEquals("https://telemetry.alecsmods.com/ingest/event", settings.endpoint());
        assertEquals(2000, settings.connectTimeoutMs());
        assertEquals(3000, settings.readTimeoutMs());
        assertEquals(200, settings.maxPendingReports());
        assertEquals(10, settings.maxUploadsPerFlush());

        assertTrue(Files.isRegularFile(file));
        String raw = Files.readString(file);
        assertTrue(raw.contains("\"enabled\": true"));
        assertTrue(raw.contains("\"breadcrumbsEnabled\": true"));
        assertTrue(raw.contains("\"breadcrumbsCapacity\": 40"));
        assertTrue(raw.contains("\"endpoint\": \"https://telemetry.alecsmods.com/ingest/event\""));
        assertFalse(raw.contains("api_key"));
    }

    @Test
    void saveEnabledWritesAndReloadsToggle() {
        Path file = tempDir.resolve(CrashTelemetrySettings.FILE_NAME);

        assertTrue(CrashTelemetrySettings.saveEnabled(file, true, null));
        CrashTelemetrySettings reloaded = CrashTelemetrySettings.load(file, null);

        assertTrue(reloaded.enabled());
    }

    @Test
    void saveTogglesWritesEnabledAndBreadcrumbsFlags() {
        Path file = tempDir.resolve(CrashTelemetrySettings.FILE_NAME);

        assertTrue(CrashTelemetrySettings.saveToggles(file, false, true, null));
        CrashTelemetrySettings reloaded = CrashTelemetrySettings.load(file, null);

        assertFalse(reloaded.enabled());
        assertTrue(reloaded.breadcrumbsEnabled());
    }

    @Test
    void saveEnabledPreservesBreadcrumbSettings() throws Exception {
        Path file = tempDir.resolve(CrashTelemetrySettings.FILE_NAME);
        Files.writeString(
                file,
                """
                        enabled=false
                        breadcrumbs_enabled=true
                        breadcrumbs_capacity=64
                        """.stripIndent()
        );

        assertTrue(CrashTelemetrySettings.saveEnabled(file, true, null));
        CrashTelemetrySettings reloaded = CrashTelemetrySettings.load(file, null);

        assertTrue(reloaded.enabled());
        assertTrue(reloaded.breadcrumbsEnabled());
        assertEquals(64, reloaded.breadcrumbsCapacity());
    }
}
