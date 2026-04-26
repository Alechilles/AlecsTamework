package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.alechilles.alecstelemetry.crash.CrashReportClient;
import com.alechilles.alecstelemetry.crash.CrashReportEnvelope;
import com.alechilles.alecstelemetry.project.TelemetryProjectDescriptor;
import com.alechilles.alecstelemetry.project.TelemetryProjectRegistration;
import com.alechilles.alecstelemetry.runtime.TelemetryDataPaths;
import com.alechilles.alecstelemetry.runtime.TelemetryRuntimeSettings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashTelemetryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void doesNotCaptureWhenDisabled() {
        CrashTelemetryService service = createService(false, true, new SequencedClient());
        service.captureSetupFailure(testThrowable("disabled"));
        assertEquals(0, service.diagnostics().pendingReports());
        assertFalse(service.diagnostics().enabled());
    }

    @Test
    void flushRetainsFailuresForRetryAcrossCrashAndEventPayloads() {
        SequencedClient client = new SequencedClient(
                CrashReportClient.UploadResult.failure(500, "server error"),
                CrashReportClient.UploadResult.success(204),
                CrashReportClient.UploadResult.success(204)
        );
        CrashTelemetryService service = createService(true, true, client);

        service.captureSetupFailure(testThrowable("crash"));
        assertTrue(service.recordLifecycle("debug_lifecycle", 123, true, "lifecycle detail"));
        assertEquals(2, service.diagnostics().pendingReports());

        CrashTelemetryService.FlushSummary first = service.flushPendingReportsNow("test-first");
        assertEquals(2, first.attempted());
        assertEquals(1, first.uploaded());
        assertEquals(1, first.pendingAfter());

        CrashTelemetryService.FlushSummary second = service.flushPendingReportsNow("test-second");
        assertEquals(1, second.attempted());
        assertEquals(1, second.uploaded());
        assertEquals(0, second.pendingAfter());
        assertEquals(3, client.calls);

        CrashTelemetryDiagnostics diagnostics = service.diagnostics();
        assertTrue(diagnostics.enabled());
        assertEquals("https://example.invalid/telemetry", diagnostics.endpoint());
        assertEquals(0, diagnostics.pendingReports());
        assertFalse(diagnostics.lastFlushResult().isBlank());
    }

    @Test
    void applyEnabledSettingUpdatesRuntimeToggle() {
        CrashTelemetryService service = createService(false, true, new SequencedClient());
        assertFalse(service.diagnostics().enabled());
        service.applyEnabledSetting(true);
        assertTrue(service.diagnostics().enabled());
        service.applyEnabledSetting(false);
        assertFalse(service.diagnostics().enabled());
    }

    @Test
    void disablingBreadcrumbsClearsBufferedBreadcrumbs() {
        SequencedClient client = new SequencedClient(CrashReportClient.UploadResult.success(204));
        CrashTelemetryService service = createService(true, true, client);
        service.recordBreadcrumb("bootstrap", "Embedded config loaded.");
        service.applyBreadcrumbsEnabledSetting(false);
        service.captureSetupFailure(testThrowable("no breadcrumbs"));
        service.flushPendingReportsNow("breadcrumbs-disabled");

        JsonObject payload = JsonParser.parseString(client.payloads.getFirst()).getAsJsonObject();
        assertEquals(0, payload.getAsJsonArray("breadcrumbs").size());
    }

    @Test
    void typedUsageContextPromotesFieldsAndValidatedDetails() {
        SequencedClient client = new SequencedClient(CrashReportClient.UploadResult.success(204));
        CrashTelemetryService service = createService(true, true, client);

        service.recordUsage(
                "debug_usage",
                TelemetryEventContext.usage()
                        .subsystem("settings")
                        .featureKey("settings_page")
                        .entryPoint("/tw settings")
                        .runtimeSide("server")
                        .detail("source", "settings_ui")
                        .detail("ignored", "drop me")
                        .build()
        );
        service.flushPendingReportsNow("typed-context");

        JsonObject payload = JsonParser.parseString(client.payloads.getFirst()).getAsJsonObject();
        assertEquals("settings", payload.get("subsystem").getAsString());
        assertEquals("settings_page", payload.get("featureKey").getAsString());
        assertEquals("/tw settings", payload.get("entryPoint").getAsString());
        assertEquals("server", payload.get("runtimeSide").getAsString());
        assertEquals("settings_ui", payload.getAsJsonObject("details").get("source").getAsString());
        assertTrue(!payload.getAsJsonObject("details").has("ignored"));
    }

    @Test
    void disabledErrorAndLifecycleEventsReturnFalseWithoutQueueing() {
        CrashTelemetryService service = createService(
                true,
                true,
                new SequencedClient(),
                """
                  "events": {
                    "errors": { "enabled": false },
                    "lifecycle": { "enabled": false }
                  },
                """
        );

        assertFalse(service.recordError("disabled_error", testThrowable("disabled error"), "detail"));
        assertFalse(service.recordLifecycle("disabled_lifecycle", 123, false, "detail"));
        assertEquals(0, service.diagnostics().pendingReports());
    }

    @Test
    void automaticBreadcrumbFlagControlsEventBreadcrumbDetails() {
        SequencedClient client = new SequencedClient(CrashReportClient.UploadResult.success(204));
        CrashTelemetryService service = createService(
                true,
                true,
                client,
                """
                  "events": {
                    "errors": { "enabled": true },
                    "breadcrumbs": { "enabled": true, "automatic": false }
                  },
                """
        );

        service.recordBreadcrumb("settings", "Opened settings.");
        assertTrue(service.recordError("debug_error", testThrowable("with breadcrumb"), "detail"));
        service.flushPendingReportsNow("automatic-breadcrumbs-disabled");

        JsonObject payload = JsonParser.parseString(client.payloads.getFirst()).getAsJsonObject();
        assertFalse(payload.getAsJsonObject("details").has("breadcrumbs"));

        service.captureSetupFailure(testThrowable("crash still gets breadcrumbs"));
        service.flushPendingReportsNow("manual-breadcrumbs-still-enabled");

        JsonObject crashPayload = JsonParser.parseString(client.payloads.getLast()).getAsJsonObject();
        var crashBreadcrumbs = crashPayload.getAsJsonArray("breadcrumbs");
        assertTrue(crashBreadcrumbs.size() >= 1);
        assertEquals("settings", crashBreadcrumbs.get(0).getAsJsonObject().get("category").getAsString());
    }

    @Test
    void typedContextDropsNullableDetailValuesBeforeCopying() {
        TelemetryEventContext context = TelemetryEventContext.usage()
                .detail("kept", 42)
                .detail("missing", null)
                .build();

        assertEquals(42, context.details().get("kept"));
        assertFalse(context.details().containsKey("missing"));
    }

    @Nonnull
    private CrashTelemetryService createService(boolean enabled,
                                                boolean breadcrumbsEnabled,
                                                CrashReportClient client) {
        return createService(enabled, breadcrumbsEnabled, client, "");
    }

    @Nonnull
    private CrashTelemetryService createService(boolean enabled,
                                                boolean breadcrumbsEnabled,
                                                CrashReportClient client,
                                                @Nonnull String extraDescriptorFields) {
        Path telemetryRoot = tempDir.resolve("Telemetry");
        Path settingsPath = tempDir.resolve("Settings").resolve(CrashTelemetrySettings.FILE_NAME);
        CrashTelemetrySettings compatibilitySettings = new CrashTelemetrySettings(
                settingsPath,
                enabled,
                breadcrumbsEnabled,
                CrashTelemetrySettings.DEFAULT_BREADCRUMBS_CAPACITY
        );
        TelemetryRuntimeSettings runtimeSettings = TelemetryRuntimeSettings.load(
                telemetryRoot.resolve("Settings").resolve("runtime.json"),
                null
        );
        TelemetryDataPaths dataPaths = new TelemetryDataPaths(
                telemetryRoot,
                runtimeSettings.filePath(),
                telemetryRoot.resolve("Settings").resolve("projects"),
                telemetryRoot,
                telemetryRoot.resolve("crash-reports"),
                telemetryRoot.resolve("events"),
                null
        );
        TelemetryProjectDescriptor descriptor = TelemetryProjectDescriptor.fromJson(
                """
                {
                  "projectId": "alecs-tamework",
                  "displayName": "Alec's Tamework!",
                  "runtimeMode": "embedded",
                  "ownerPluginIdentifiers": ["Alechilles:Alec's Tamework!"],
                  "packagePrefixes": ["com.alechilles.alecstamework"],
                %s
                  "performance": {
                    "enabled": true,
                    "sampleRate": 1.0,
                    "thresholdMs": 100
                  },
                  "usage": {
                    "enabled": true,
                    "allowedEvents": ["debug_usage"],
                    "details": {
                      "debug_usage": {
                        "allowedFields": {
                          "source": { "type": "enum", "values": ["command", "settings_ui"] }
                        }
                      }
                    }
                  },
                  "defaults": {
                    "enabled": true,
                    "destinationMode": "custom"
                  },
                  "customEndpoint": {
                    "url": "https://example.invalid/telemetry"
                  }
                }
                """.formatted(extraDescriptorFields),
                null
        );
        TelemetryProjectRegistration registration = new TelemetryProjectRegistration(
                descriptor,
                "Alechilles:Alec's Tamework!",
                "2.8.5",
                tempDir.resolve("Alec's Tamework! v2.8.5.jar")
        );
        return new CrashTelemetryService(
                compatibilitySettings,
                runtimeSettings,
                dataPaths,
                registration,
                client,
                null,
                null,
                List.of(new CrashReportEnvelope.LoadedModMetadata("Alechilles:Alec's Tamework!", "2.8.5"))
        );
    }

    @Nonnull
    private static RuntimeException testThrowable(String message) {
        RuntimeException throwable = new RuntimeException(message);
        throwable.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.alechilles.alecstamework.TestSystem", "tick", "TestSystem.java", 22)
        });
        return throwable;
    }

    private static final class SequencedClient implements CrashReportClient {
        private final Queue<UploadResult> responses = new ArrayDeque<>();
        private final ArrayDeque<String> payloads = new ArrayDeque<>();
        private int calls;

        private SequencedClient(UploadResult... uploadResults) {
            for (UploadResult uploadResult : uploadResults) {
                responses.add(uploadResult);
            }
        }

        @Override
        public UploadResult upload(DeliveryTarget target, String payloadJson) {
            calls++;
            payloads.add(payloadJson);
            UploadResult next = responses.poll();
            return next == null ? UploadResult.success(200) : next;
        }
    }
}
