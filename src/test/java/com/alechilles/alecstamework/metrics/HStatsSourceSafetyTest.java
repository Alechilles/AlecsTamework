package com.alechilles.alecstamework.metrics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HStatsSourceSafetyTest {
    private static final Path HSTATS_SOURCE = Path.of("src/main/java/com/alechilles/alecstamework/metrics/HStats.java");
    private static final Path INTEGRATION_SOURCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/metrics/TameworkHStatsIntegration.java"
    );

    @Test
    void hStatsHttpRequestsUseTimeouts() throws Exception {
        String source = Files.readString(HSTATS_SOURCE);

        assertTrue(source.contains("setConnectTimeout("), "HStats HTTP requests must set a connect timeout");
        assertTrue(source.contains("setReadTimeout("), "HStats HTTP requests must set a read timeout");
    }

    @Test
    void metricsWorkDoesNotRunOnHytaleScheduler() throws Exception {
        String hStatsSource = Files.readString(HSTATS_SOURCE);
        String integrationSource = Files.readString(INTEGRATION_SOURCE);

        assertFalse(
                hStatsSource.contains("HytaleServer.SCHEDULED_EXECUTOR"),
                "HStats must not schedule blocking HTTP work on the engine scheduler"
        );
        assertFalse(
                integrationSource.contains("HytaleServer.SCHEDULED_EXECUTOR"),
                "Tamework metrics forwarding must not run blocking I/O on the engine scheduler"
        );
        assertTrue(
                hStatsSource.contains("newSingleThreadScheduledExecutor"),
                "HStats periodic reporting should use a dedicated daemon scheduler"
        );
        assertTrue(
                integrationSource.contains("newSingleThreadExecutor"),
                "Dependency metrics forwarding should use a dedicated daemon executor"
        );
    }
}
