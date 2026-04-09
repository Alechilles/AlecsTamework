package com.alechilles.alecstamework.metrics;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashTelemetryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void doesNotCaptureWhenDisabled() {
        CrashTelemetrySettings settings = new CrashTelemetrySettings(
                tempDir.resolve("crash-telemetry.json"),
                false
        );
        CrashReportStore store = new CrashReportStore(tempDir.resolve("pending"), settings.maxPendingReports(), null);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CrashTelemetryService service = new CrashTelemetryService(
                    settings,
                    store,
                    null,
                    new PluginIdentifier("Alechilles", "Alec's Tamework!"),
                    "2.7.3",
                    null,
                    executor
            );

            RuntimeException throwable = new RuntimeException("disabled");
            throwable.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.alechilles.alecstamework.Disabled", "run", "Disabled.java", 4)
            });
            service.captureSetupFailure(throwable);

            assertEquals(0, store.pendingCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void flushRetainsFailuresForRetry() {
        CrashTelemetrySettings settings = new CrashTelemetrySettings(
                tempDir.resolve("crash-telemetry.json"),
                true
        );
        CrashReportStore store = new CrashReportStore(tempDir.resolve("pending"), 20, null);
        SequencedClient client = new SequencedClient(
                CrashReportClient.UploadResult.failure(500, "server error"),
                CrashReportClient.UploadResult.success(204)
        );
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CrashTelemetryService service = new CrashTelemetryService(
                    settings,
                    store,
                    client,
                    new PluginIdentifier("Alechilles", "Alec's Tamework!"),
                    "2.7.3",
                    null,
                    executor
            );

            store.persist(testReport("fingerprint-one", "one"));
            store.persist(testReport("fingerprint-two", "two"));
            assertEquals(2, store.pendingCount());

            CrashTelemetryService.FlushSummary first = service.flushPendingReportsNow("test-first");
            assertEquals(2, first.attempted());
            assertEquals(1, first.uploaded());
            assertEquals(1, store.pendingCount());

            CrashTelemetryService.FlushSummary second = service.flushPendingReportsNow("test-second");
            assertEquals(1, second.attempted());
            assertEquals(1, second.uploaded());
            assertEquals(0, store.pendingCount());
            assertEquals(3, client.calls);

            CrashTelemetryDiagnostics diagnostics = service.diagnostics();
            assertTrue(diagnostics.enabled());
            assertEquals("https://telemetry.alecsmods.com/tamework/crash-report", diagnostics.endpoint());
            assertEquals(0, diagnostics.pendingReports());
            assertFalse(diagnostics.lastFlushResult().isBlank());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void applyEnabledSettingUpdatesRuntimeToggle() {
        CrashTelemetrySettings settings = new CrashTelemetrySettings(
                tempDir.resolve("crash-telemetry.json"),
                false
        );
        CrashReportStore store = new CrashReportStore(tempDir.resolve("pending"), settings.maxPendingReports(), null);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CrashTelemetryService service = new CrashTelemetryService(
                    settings,
                    store,
                    new SequencedClient(CrashReportClient.UploadResult.success(204)),
                    new PluginIdentifier("Alechilles", "Alec's Tamework!"),
                    "2.7.3",
                    null,
                    executor
            );

            assertFalse(service.diagnostics().enabled());
            service.applyEnabledSetting(true);
            assertTrue(service.diagnostics().enabled());
            service.applyEnabledSetting(false);
            assertFalse(service.diagnostics().enabled());
        } finally {
            executor.shutdownNow();
        }
    }

    private static CrashReportEnvelope testReport(String fingerprint, String message) {
        RuntimeException throwable = new RuntimeException(message);
        throwable.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.alechilles.alecstamework.TestSystem", "tick", "TestSystem.java", 22)
        });
        CrashAttribution.AttributionResult attribution = CrashAttribution.classify(
                throwable,
                new PluginIdentifier("Alechilles", "Alec's Tamework!")
        );
        return CrashReportEnvelope.create(
                "unit_test",
                fingerprint,
                "Alechilles:Alec's Tamework!",
                "2.7.3",
                "TestThread",
                null,
                null,
                null,
                attribution,
                throwable
        );
    }

    private static final class SequencedClient implements CrashReportClient {
        private final Queue<UploadResult> responses = new ArrayDeque<>();
        private int calls;

        private SequencedClient(UploadResult... uploadResults) {
            for (UploadResult uploadResult : uploadResults) {
                responses.add(uploadResult);
            }
        }

        @Override
        public UploadResult upload(String payloadJson) {
            calls++;
            UploadResult next = responses.poll();
            return next == null ? UploadResult.success(200) : next;
        }
    }
}
