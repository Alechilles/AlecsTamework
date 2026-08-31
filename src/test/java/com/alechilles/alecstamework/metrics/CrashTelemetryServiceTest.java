package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.alechilles.alecstelemetry.api.TelemetryDiagnosticBundle;
import com.alechilles.alecstelemetry.api.TelemetryDiagnosticBundleResult;
import com.alechilles.alecstelemetry.api.TelemetryDiagnosticDisposition;
import com.alechilles.alecstelemetry.embedded.EmbeddedTelemetryDiagnostics;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashTelemetryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void doesNotCaptureWhenDisabled() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        CrashTelemetryService service = createService(false, true, runtime);

        service.captureSetupFailure(testThrowable("disabled"));

        assertEquals(0, runtime.setupFailureCalls);
        assertFalse(service.diagnostics().enabled());
        assertEquals(true, runtime.lastProjectEnabled);
    }

    @Test
    void applyEnabledSettingUpdatesRuntimeToggle() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        CrashTelemetryService service = createService(false, true, runtime);

        assertFalse(service.diagnostics().enabled());
        assertEquals(true, runtime.lastProjectEnabled);

        service.applyEnabledSetting(true);
        assertTrue(service.diagnostics().enabled());
        assertEquals(1, runtime.startCalls);
        assertEquals(true, runtime.lastProjectEnabled);

        service.applyEnabledSetting(false);
        assertFalse(service.diagnostics().enabled());
        assertEquals(0, runtime.shutdownCalls);
        assertEquals(true, runtime.lastProjectEnabled);
    }

    @Test
    void startsEmbeddedRuntimeWhenCrashTelemetryDisabledSoStatsCanRun() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        CrashTelemetryService service = createService(false, true, runtime);

        service.start();

        assertEquals(1, runtime.startCalls);
        assertEquals(true, runtime.lastProjectEnabled);
        assertFalse(service.recordError("disabled_error", testThrowable("disabled"), "detail"));
        assertEquals(0, runtime.errorCalls);
    }

    @Test
    void disablingBreadcrumbsClearsBufferedBreadcrumbs() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        CrashTelemetryService service = createService(true, true, runtime);

        service.recordBreadcrumb("bootstrap", "Embedded config loaded.");
        service.applyBreadcrumbsEnabledSetting(false);
        service.recordBreadcrumb("settings", "This should not be forwarded.");

        assertEquals(1, runtime.breadcrumbCalls);
        assertEquals(1, runtime.clearBreadcrumbsCalls);
        assertEquals(false, runtime.lastBreadcrumbsEnabled);
    }

    @Test
    void typedUsageContextIsForwardedToEmbeddedRuntime() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        CrashTelemetryService service = createService(true, true, runtime);
        TelemetryEventContext context = TelemetryEventContext.usage()
                .subsystem("settings")
                .featureKey("settings_page")
                .entryPoint("/tw settings")
                .runtimeSide("server")
                .detail("source", "settings_ui")
                .build();

        service.recordUsage("debug_usage", context);

        assertEquals("debug_usage", runtime.lastUsageEvent);
        assertSame(context, runtime.lastUsageContext);
    }

    @Test
    void recordErrorAndLifecycleReturnFalseWhenEmbeddedRuntimeDisabled() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        runtime.enabled = false;
        CrashTelemetryService service = createService(true, true, runtime);

        assertFalse(service.recordError("disabled_error", testThrowable("disabled"), "detail"));
        assertFalse(service.recordLifecycle("disabled_lifecycle", 123, false, "detail"));
        assertEquals(0, runtime.errorCalls);
        assertEquals(0, runtime.lifecycleCalls);
    }

    @Test
    void recordErrorAndLifecycleReturnFalseWhenEndpointDisabled() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        runtime.endpoint = "<disabled>";
        CrashTelemetryService service = createService(true, true, runtime);

        assertFalse(service.recordError("disabled_error", testThrowable("disabled"), "detail"));
        assertFalse(service.recordLifecycle("disabled_lifecycle", 123, false, "detail"));
        assertEquals(0, runtime.errorCalls);
        assertEquals(0, runtime.lifecycleCalls);
    }

    @Test
    void recordErrorAndLifecycleForwardRequestsWhenRuntimeAvailable() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        CrashTelemetryService service = createService(true, true, runtime);
        RuntimeException throwable = testThrowable("recorded");

        assertTrue(service.recordError("debug_error", throwable, "error detail"));
        assertTrue(service.recordLifecycle("debug_lifecycle", 123, true, "lifecycle detail"));

        assertEquals("debug_error", runtime.lastErrorEvent);
        assertSame(throwable, runtime.lastErrorThrowable);
        assertEquals("debug_lifecycle", runtime.lastLifecycleEvent);
        assertEquals(123, runtime.lastLifecycleDurationMs);
        assertTrue(runtime.lastLifecycleSuccess);
    }

    @Test
    void diagnosticBundleUsesHostedEventSettingAndRuntime() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        CrashTelemetryService service = createService(true, true, runtime);
        TelemetryDiagnosticBundle bundle = new TelemetryDiagnosticBundle(
                "diagnostic-id", "2026-08-30T12:00:00Z", "automatic",
                "persistence_failure", "Persistence failure", "Safe summary",
                "error", TelemetryDiagnosticDisposition.createOrJoinIssue("fingerprint"),
                Map.of(), List.of()
        );

        TelemetryDiagnosticBundleResult result = service.submitDiagnosticBundle(bundle);

        assertEquals(TelemetryDiagnosticBundleResult.Status.QUEUED, result.status());
        assertSame(bundle, runtime.lastDiagnosticBundle);
        service.applyEnabledSetting(false);
        assertEquals(
                TelemetryDiagnosticBundleResult.Status.DISABLED,
                service.submitDiagnosticBundle(bundle).status()
        );
        assertSame(bundle, runtime.lastDiagnosticBundle);
    }

    @Test
    void diagnosticsExposeEmbeddedStateAndAdapterLastStatus() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        runtime.pendingReports = 7;
        runtime.flushInProgress = true;
        runtime.lastFlushResult = "Runtime flush failed.";
        CrashTelemetryService service = createService(true, true, runtime);

        CrashTelemetryDiagnostics diagnostics = service.diagnostics();

        assertTrue(diagnostics.enabled());
        assertEquals(runtime.endpoint, diagnostics.endpoint());
        assertEquals(7, diagnostics.pendingReports());
        assertTrue(diagnostics.flushInProgress());
        assertEquals("Runtime flush failed.", diagnostics.lastFlushResult());
        assertTrue(diagnostics.lastFlushEpochMs() > 0);
    }

    @Test
    void flushPendingReportsNowRequestsAsyncFlushForCompatibility() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        runtime.pendingReports = 3;
        CrashTelemetryService service = createService(true, true, runtime);

        CrashTelemetryService.FlushSummary summary = service.flushPendingReportsNow("test");

        assertEquals(1, runtime.flushRequests);
        assertEquals(1, summary.attempted());
        assertEquals(0, summary.uploaded());
        assertEquals(3, summary.pendingAfter());
        assertNull(summary.lastFailure());
    }

    @Test
    void flushPendingReportsNowReportsUnavailableFlush() {
        FakeEmbeddedRuntime runtime = new FakeEmbeddedRuntime();
        runtime.requestFlushResult = false;
        runtime.lastFlushResult = "Flush executor unavailable.";
        CrashTelemetryService service = createService(true, true, runtime);

        CrashTelemetryService.FlushSummary summary = service.flushPendingReportsNow("test");

        assertEquals(1, runtime.flushRequests);
        assertEquals(0, summary.attempted());
        assertEquals("Flush executor unavailable.", summary.lastFailure());
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

    @Test
    void legacySettingsCandidatesIncludePriorUniverseSettingsPath() {
        Path pluginData = tempDir.resolve("plugin-data");
        Path universe = tempDir.resolve("universe").resolve("Tamework");

        List<Path> candidates = CrashTelemetryService.legacyCrashTelemetrySettingsCandidates(pluginData, universe);

        assertTrue(candidates.contains(universe.resolve("Settings").resolve("crash-telemetry.json").toAbsolutePath().normalize()));
    }

    @Test
    void migratesLegacyLowercaseTelemetryDirectoryIntoStandardEmbeddedDirectory() throws Exception {
        Path target = tempDir.resolve("plugin-data").resolve("Telemetry");
        Path legacyLowercase = tempDir.resolve("plugin-data").resolve("telemetry");
        Path pendingReport = legacyLowercase.resolve("crash-reports").resolve("pending").resolve("report.json");
        Path serverId = legacyLowercase.resolve("Settings").resolve("server-id.txt");
        Files.createDirectories(pendingReport.getParent());
        Files.createDirectories(serverId.getParent());
        Files.writeString(pendingReport, "{}", StandardCharsets.UTF_8);
        Files.writeString(serverId, "legacy-server", StandardCharsets.UTF_8);

        CrashTelemetryService.migrateLegacyTelemetryData(target, List.of(legacyLowercase), null);

        assertTrue(Files.isRegularFile(target.resolve("crash-reports").resolve("alecs-tamework").resolve("pending").resolve("report.json")));
        assertEquals("legacy-server", Files.readString(target.resolve("Settings").resolve("server-id.txt"), StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(legacyLowercase));
    }

    @Test
    void copiesOnlyTameworkOwnedFilesFromSharedLegacyTelemetryRoot() throws Exception {
        Path target = tempDir.resolve("plugin-data").resolve("Telemetry");
        Path sharedRoot = tempDir.resolve("universe").resolve("Telemetry");
        Path pendingReport = sharedRoot.resolve("crash-reports").resolve("alecs-tamework").resolve("pending").resolve("report.json");
        Path pendingEvent = sharedRoot.resolve("events").resolve("alecs-tamework").resolve("pending").resolve("event.json");
        Path serverId = sharedRoot.resolve("Settings").resolve("server-id.txt");
        Path tameworkProjectSettings = sharedRoot.resolve("Settings").resolve("projects").resolve("alecs-tamework.json");
        Path sharedPendingReport = sharedRoot.resolve("crash-reports").resolve("pending").resolve("shared-report.json");
        Path sharedPendingEvent = sharedRoot.resolve("events").resolve("pending").resolve("shared-event.json");
        Path unrelatedPluginReport = sharedRoot.resolve("crash-reports").resolve("other-project").resolve("pending").resolve("report.json");
        Path unrelatedProjectSettings = sharedRoot.resolve("Settings").resolve("projects").resolve("other-project.json");
        Path unrelatedRootFile = sharedRoot.resolve("unrelated.txt");
        Files.createDirectories(pendingReport.getParent());
        Files.createDirectories(pendingEvent.getParent());
        Files.createDirectories(serverId.getParent());
        Files.createDirectories(tameworkProjectSettings.getParent());
        Files.createDirectories(sharedPendingReport.getParent());
        Files.createDirectories(sharedPendingEvent.getParent());
        Files.createDirectories(unrelatedPluginReport.getParent());
        Files.createDirectories(unrelatedProjectSettings.getParent());
        Files.writeString(pendingReport, "{}", StandardCharsets.UTF_8);
        Files.writeString(pendingEvent, "{}", StandardCharsets.UTF_8);
        Files.writeString(serverId, "shared-server", StandardCharsets.UTF_8);
        Files.writeString(tameworkProjectSettings, "{}", StandardCharsets.UTF_8);
        Files.writeString(sharedPendingReport, "{}", StandardCharsets.UTF_8);
        Files.writeString(sharedPendingEvent, "{}", StandardCharsets.UTF_8);
        Files.writeString(unrelatedPluginReport, "{}", StandardCharsets.UTF_8);
        Files.writeString(unrelatedProjectSettings, "{}", StandardCharsets.UTF_8);
        Files.writeString(unrelatedRootFile, "shared", StandardCharsets.UTF_8);

        CrashTelemetryService.migrateLegacyTelemetryData(target, List.of(sharedRoot), null);

        assertTrue(Files.isRegularFile(target.resolve("crash-reports").resolve("alecs-tamework").resolve("pending").resolve("report.json")));
        assertTrue(Files.isRegularFile(target.resolve("events").resolve("alecs-tamework").resolve("pending").resolve("event.json")));
        assertEquals("shared-server", Files.readString(target.resolve("Settings").resolve("server-id.txt"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(target.resolve("Settings").resolve("projects").resolve("alecs-tamework.json")));
        assertFalse(Files.exists(target.resolve("crash-reports").resolve("alecs-tamework").resolve("pending").resolve("shared-report.json")));
        assertFalse(Files.exists(target.resolve("events").resolve("alecs-tamework").resolve("pending").resolve("shared-event.json")));
        assertFalse(Files.exists(target.resolve("crash-reports").resolve("other-project")));
        assertFalse(Files.exists(target.resolve("Settings").resolve("projects").resolve("other-project.json")));
        assertFalse(Files.exists(target.resolve("unrelated.txt")));
        assertTrue(Files.isDirectory(sharedRoot));
        assertTrue(Files.isRegularFile(unrelatedPluginReport));
        assertTrue(Files.isRegularFile(sharedPendingReport));
    }

    @Test
    void migratesTelemetryDataAcrossMultipleLegacyRoots() throws Exception {
        Path target = tempDir.resolve("plugin-data").resolve("Telemetry");
        Path sharedRoot = tempDir.resolve("universe").resolve("Telemetry");
        Path pluginLocalRoot = tempDir.resolve("plugin-data").resolve("telemetry");
        Path sharedReport = sharedRoot.resolve("crash-reports").resolve("alecs-tamework").resolve("pending").resolve("shared-report.json");
        Path localReport = pluginLocalRoot.resolve("crash-reports").resolve("pending").resolve("local-report.json");
        Files.createDirectories(sharedReport.getParent());
        Files.createDirectories(localReport.getParent());
        Files.writeString(sharedReport, "{}", StandardCharsets.UTF_8);
        Files.writeString(localReport, "{}", StandardCharsets.UTF_8);

        CrashTelemetryService.migrateLegacyTelemetryData(target, List.of(sharedRoot, pluginLocalRoot), null);

        assertTrue(Files.isRegularFile(target.resolve("crash-reports").resolve("alecs-tamework").resolve("pending").resolve("shared-report.json")));
        assertTrue(Files.isRegularFile(target.resolve("crash-reports").resolve("alecs-tamework").resolve("pending").resolve("local-report.json")));
    }

    @Test
    void doesNotOverwriteNonEmptyEmbeddedTelemetryDirectory() throws Exception {
        Path target = tempDir.resolve("plugin-data").resolve("Telemetry");
        Path legacyLowercase = tempDir.resolve("legacy-plugin-data").resolve("telemetry");
        Files.createDirectories(target);
        Files.writeString(target.resolve("existing.txt"), "keep", StandardCharsets.UTF_8);
        Files.createDirectories(legacyLowercase);
        Files.writeString(legacyLowercase.resolve("legacy.txt"), "legacy", StandardCharsets.UTF_8);

        CrashTelemetryService.migrateLegacyTelemetryData(target, List.of(legacyLowercase), null);

        assertTrue(Files.isRegularFile(target.resolve("existing.txt")));
        assertFalse(Files.exists(target.resolve("legacy.txt")));
    }

    @Nonnull
    private CrashTelemetryService createService(boolean enabled,
                                                boolean breadcrumbsEnabled,
                                                @Nonnull FakeEmbeddedRuntime runtime) {
        return new CrashTelemetryService(enabled, breadcrumbsEnabled, runtime);
    }

    @Nonnull
    private static RuntimeException testThrowable(String message) {
        RuntimeException throwable = new RuntimeException(message);
        throwable.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.alechilles.alecstamework.TestSystem", "tick", "TestSystem.java", 22)
        });
        return throwable;
    }

    private static final class FakeEmbeddedRuntime implements CrashTelemetryService.EmbeddedRuntime {
        private boolean enabled = true;
        private String disabledReason;
        private String endpoint = "https://example.invalid/telemetry";
        private int pendingReports;
        private boolean flushInProgress;
        private String lastFlushResult = "No flush attempts yet.";
        private boolean requestFlushResult = true;

        private int startCalls;
        private int shutdownCalls;
        private int breadcrumbCalls;
        private int clearBreadcrumbsCalls;
        private int projectEnabledCalls;
        private int breadcrumbsEnabledCalls;
        private int setupFailureCalls;
        private int errorCalls;
        private int lifecycleCalls;
        private int flushRequests;

        private Boolean lastProjectEnabled;
        private Boolean lastBreadcrumbsEnabled;
        private String lastErrorEvent;
        private Throwable lastErrorThrowable;
        private String lastLifecycleEvent;
        private int lastLifecycleDurationMs;
        private boolean lastLifecycleSuccess;
        private String lastUsageEvent;
        private TelemetryEventContext lastUsageContext;
        private TelemetryDiagnosticBundle lastDiagnosticBundle;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Nullable
        @Override
        public String disabledReason() {
            return disabledReason;
        }

        @Override
        public void start() {
            startCalls++;
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
        }

        @Override
        public void recordBreadcrumb(@Nonnull String category, @Nonnull String detail) {
            breadcrumbCalls++;
        }

        @Override
        public void clearBreadcrumbs() {
            clearBreadcrumbsCalls++;
        }

        @Override
        public boolean setProjectEnabled(boolean enabled) {
            projectEnabledCalls++;
            lastProjectEnabled = enabled;
            return true;
        }

        @Override
        public boolean setBreadcrumbsEnabled(boolean enabled) {
            breadcrumbsEnabledCalls++;
            lastBreadcrumbsEnabled = enabled;
            return true;
        }

        @Override
        public void captureSetupFailure(@Nullable Throwable throwable) {
            setupFailureCalls++;
        }

        @Override
        public void captureStartFailure(@Nullable Throwable throwable) {
        }

        @Override
        public void captureExceptionalWorldRemoval(@Nullable World world,
                                                   @Nullable RemoveWorldEvent.RemovalReason removalReason) {
        }

        @Override
        public void recordErrorWithContext(@Nonnull String eventName,
                                           @Nullable Throwable throwable,
                                           @Nullable TelemetryEventContext context) {
            errorCalls++;
            lastErrorEvent = eventName;
            lastErrorThrowable = throwable;
        }

        @Override
        public void recordLifecycleWithContext(@Nonnull String eventName,
                                               int durationMs,
                                               boolean success,
                                               @Nullable TelemetryEventContext context) {
            lifecycleCalls++;
            lastLifecycleEvent = eventName;
            lastLifecycleDurationMs = durationMs;
            lastLifecycleSuccess = success;
        }

        @Override
        public void recordPerformanceWithContext(@Nonnull String eventName,
                                                 int durationMs,
                                                 @Nullable Double metricValue,
                                                 @Nullable TelemetryEventContext context) {
        }

        @Override
        public void recordUsageWithContext(@Nonnull String eventName,
                                           @Nullable TelemetryEventContext context) {
            lastUsageEvent = eventName;
            lastUsageContext = context;
        }

        @Nonnull
        @Override
        public TelemetryDiagnosticBundleResult submitDiagnosticBundle(
                @Nonnull TelemetryDiagnosticBundle bundle
        ) {
            lastDiagnosticBundle = bundle;
            return new TelemetryDiagnosticBundleResult(
                    TelemetryDiagnosticBundleResult.Status.QUEUED, null
            );
        }

        @Override
        public boolean requestFlush() {
            flushRequests++;
            return requestFlushResult;
        }

        @Nonnull
        @Override
        public EmbeddedTelemetryDiagnostics diagnostics() {
            return new EmbeddedTelemetryDiagnostics(
                    enabled,
                    endpoint,
                    pendingReports,
                    flushInProgress,
                    lastFlushResult
            );
        }
    }
}
