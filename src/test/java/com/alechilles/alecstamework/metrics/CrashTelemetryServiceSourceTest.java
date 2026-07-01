package com.alechilles.alecstamework.metrics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrashTelemetryServiceSourceTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/metrics/CrashTelemetryService.java"
    );

    @Test
    void legacyTelemetryMigrationRunsOnDedicatedDaemonExecutor() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("legacyMigrationExecutor"), "legacy telemetry migration should have its own executor");
        assertTrue(source.contains("legacyMigrationExecutor.execute("), "legacy migration should not run inline during create()");
        assertTrue(source.contains("thread.setDaemon(true)"), "legacy migration executor must not keep the server alive");
        assertTrue(source.contains("legacyMigrationExecutor.shutdownNow()"), "service shutdown should close migration work");
    }
}
