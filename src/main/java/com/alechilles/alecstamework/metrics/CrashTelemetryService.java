package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.alechilles.alecstelemetry.embedded.EmbeddedTelemetryBootstrap;
import com.alechilles.alecstelemetry.embedded.EmbeddedTelemetryDiagnostics;
import com.alechilles.alecstelemetry.embedded.EmbeddedTelemetryService;
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Tamework compatibility layer over the standard embedded Alec's Telemetry runtime.
 */
public final class CrashTelemetryService {

    private final EmbeddedRuntime telemetry;
    private final AtomicBoolean enabled;
    private final AtomicBoolean breadcrumbsEnabled;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile String lastFlushResult = "No flush attempts yet.";
    private volatile long lastFlushEpochMs;

    @Nonnull
    public static CrashTelemetryService create(@Nonnull Tamework plugin) {
        Objects.requireNonNull(plugin, "plugin");
        HytaleLogger logger = plugin.getLogger();
        Path compatibilitySettingsPath = TameworkSettingsStore.resolveSettingsDirectory(plugin)
                .resolve(CrashTelemetrySettings.FILE_NAME)
                .toAbsolutePath()
                .normalize();
        CrashTelemetrySettings compatibilitySettings = CrashTelemetrySettings.load(compatibilitySettingsPath, logger);
        migrateLegacyTelemetryData(plugin, logger);
        EmbeddedTelemetryService embeddedTelemetry = EmbeddedTelemetryBootstrap.bootstrap(plugin);
        return new CrashTelemetryService(compatibilitySettings, new EmbeddedServiceRuntime(embeddedTelemetry));
    }

    CrashTelemetryService(@Nonnull CrashTelemetrySettings compatibilitySettings,
                          @Nonnull EmbeddedRuntime telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.enabled = new AtomicBoolean(compatibilitySettings.enabled());
        this.breadcrumbsEnabled = new AtomicBoolean(compatibilitySettings.breadcrumbsEnabled());
        syncLastFlushStatus();
    }

    public synchronized void start() {
        syncLastFlushStatus();
        if (!isRuntimeEnabled()) {
            updateLastFlushStatus(disabledReason());
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (breadcrumbsEnabled.get()) {
            telemetry.recordBreadcrumb("lifecycle", "Embedded telemetry started.");
        }
        telemetry.start();
        syncLastFlushStatus();
    }

    public synchronized void shutdown() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (breadcrumbsEnabled.get()) {
            telemetry.recordBreadcrumb("lifecycle", "Embedded telemetry shutdown.");
        }
        telemetry.shutdown();
        syncLastFlushStatus();
    }

    public synchronized void applyEnabledSetting(boolean enabled) {
        boolean previous = this.enabled.getAndSet(enabled);
        if (previous == enabled) {
            return;
        }
        if (enabled) {
            updateLastFlushStatus("Embedded telemetry enabled.");
            start();
            if (breadcrumbsEnabled.get()) {
                telemetry.recordBreadcrumb("settings", "Embedded telemetry enabled via runtime setting.");
            }
            return;
        }
        if (breadcrumbsEnabled.get()) {
            telemetry.recordBreadcrumb("settings", "Embedded telemetry disabled via runtime setting.");
        }
        shutdown();
        updateLastFlushStatus("Embedded telemetry disabled by settings.");
    }

    public synchronized void applyBreadcrumbsEnabledSetting(boolean enabled) {
        boolean previous = this.breadcrumbsEnabled.getAndSet(enabled);
        if (previous == enabled) {
            return;
        }
        if (enabled) {
            if (isRuntimeEnabled()) {
                telemetry.recordBreadcrumb("settings", "Embedded telemetry breadcrumbs enabled via runtime setting.");
            }
            return;
        }
        telemetry.clearBreadcrumbs();
    }

    public void recordBreadcrumb(@Nonnull String category, @Nonnull String detail) {
        if (!isRuntimeEnabled() || !breadcrumbsEnabled.get()) {
            return;
        }
        telemetry.recordBreadcrumb(category, detail);
    }

    public void captureSetupFailure(@Nullable Throwable throwable) {
        if (!isRuntimeEnabled() || throwable == null) {
            return;
        }
        recordBreadcrumb("capture", "Capturing setup failure.");
        telemetry.captureSetupFailure(throwable);
        syncLastFlushStatus();
    }

    public void captureStartFailure(@Nullable Throwable throwable) {
        if (!isRuntimeEnabled() || throwable == null) {
            return;
        }
        recordBreadcrumb("capture", "Capturing start failure.");
        telemetry.captureStartFailure(throwable);
        syncLastFlushStatus();
    }

    public void captureExceptionalWorldRemoval(@Nullable World world,
                                               @Nullable RemoveWorldEvent.RemovalReason removalReason) {
        if (!isRuntimeEnabled() || world == null || removalReason != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            return;
        }
        recordBreadcrumb("capture", "Capturing exceptional world removal for " + safeWorldName(world) + ".");
        telemetry.captureExceptionalWorldRemoval(world, removalReason);
        syncLastFlushStatus();
    }

    public boolean recordError(@Nonnull String eventName,
                               @Nullable Throwable throwable,
                               @Nullable String detail) {
        return recordError(eventName, throwable, TelemetryEventContext.builder().detail(detail).build());
    }

    public boolean recordError(@Nonnull String eventName,
                               @Nullable Throwable throwable,
                               @Nullable TelemetryEventContext context) {
        if (!canRecordEvents()) {
            return false;
        }
        telemetry.recordErrorWithContext(eventName, throwable, context);
        syncLastFlushStatus();
        return true;
    }

    public boolean recordLifecycle(@Nonnull String eventName,
                                   int durationMs,
                                   boolean success,
                                   @Nullable String detail) {
        return recordLifecycle(eventName, durationMs, success, TelemetryEventContext.builder().detail(detail).build());
    }

    public boolean recordLifecycle(@Nonnull String eventName,
                                   int durationMs,
                                   boolean success,
                                   @Nullable TelemetryEventContext context) {
        if (!canRecordEvents()) {
            return false;
        }
        telemetry.recordLifecycleWithContext(eventName, durationMs, success, context);
        syncLastFlushStatus();
        return true;
    }

    public void recordPerformance(@Nonnull String eventName,
                                  int durationMs,
                                  @Nullable Double metricValue,
                                  @Nullable String detail) {
        recordPerformance(eventName, durationMs, metricValue, TelemetryEventContext.builder().detail(detail).build());
    }

    public void recordPerformance(@Nonnull String eventName,
                                  int durationMs,
                                  @Nullable Double metricValue,
                                  @Nullable TelemetryEventContext context) {
        if (!canRecordEvents()) {
            return;
        }
        telemetry.recordPerformanceWithContext(eventName, durationMs, metricValue, context);
        syncLastFlushStatus();
    }

    public void recordUsage(@Nonnull String eventName,
                            @Nullable String detail) {
        recordUsage(eventName, TelemetryEventContext.builder().detail(detail).build());
    }

    public void recordUsage(@Nonnull String eventName,
                            @Nullable TelemetryEventContext context) {
        if (!canRecordEvents()) {
            return;
        }
        telemetry.recordUsageWithContext(eventName, context);
        syncLastFlushStatus();
    }

    public boolean triggerFlushAsync() {
        if (!isRuntimeEnabled()) {
            return false;
        }
        recordBreadcrumb("flush", "Manual embedded telemetry flush requested.");
        boolean scheduled = telemetry.requestFlush();
        syncLastFlushStatus();
        return scheduled;
    }

    @Nonnull
    public CrashTelemetryDiagnostics diagnostics() {
        syncLastFlushStatus();
        EmbeddedTelemetryDiagnostics diagnostics = telemetry.diagnostics();
        return new CrashTelemetryDiagnostics(
                isRuntimeEnabled(),
                diagnostics.endpoint(),
                diagnostics.pendingReports(),
                diagnostics.flushInProgress(),
                lastFlushResult,
                lastFlushEpochMs
        );
    }

    @Nonnull
    FlushSummary flushPendingReportsNow(@Nonnull String reason) {
        boolean requested = triggerFlushAsync();
        CrashTelemetryDiagnostics diagnostics = diagnostics();
        return new FlushSummary(
                requested ? 1 : 0,
                0,
                diagnostics.pendingReports(),
                requested ? null : diagnostics.lastFlushResult()
        );
    }

    private boolean isRuntimeEnabled() {
        return enabled.get() && telemetry.isEnabled();
    }

    private boolean canRecordEvents() {
        return isRuntimeEnabled() && !"<disabled>".equals(telemetry.diagnostics().endpoint());
    }

    private void syncLastFlushStatus() {
        String embeddedStatus = telemetry.diagnostics().lastFlushResult();
        if (embeddedStatus == null || embeddedStatus.isBlank() || embeddedStatus.equals(lastFlushResult)) {
            return;
        }
        updateLastFlushStatus(embeddedStatus);
    }

    private void updateLastFlushStatus(@Nonnull String status) {
        lastFlushResult = status;
        lastFlushEpochMs = System.currentTimeMillis();
    }

    @Nonnull
    private String disabledReason() {
        if (!enabled.get()) {
            return "Embedded telemetry disabled by Tamework settings.";
        }
        String reason = telemetry.disabledReason();
        return reason == null || reason.isBlank() ? "Embedded telemetry is unavailable." : reason;
    }

    @Nonnull
    private static String safeWorldName(@Nonnull World world) {
        String name = world.getName();
        return name == null || name.isBlank() ? "<unknown-world>" : name.trim();
    }

    private static void migrateLegacyTelemetryData(@Nonnull Tamework plugin, @Nullable HytaleLogger logger) {
        Path oldTelemetryRoot = TameworkSettingsStore.resolveTameworkUniverseRoot(plugin)
                .resolve("Telemetry")
                .toAbsolutePath()
                .normalize();
        Path newTelemetryRoot = plugin.getDataDirectory()
                .toAbsolutePath()
                .normalize()
                .resolve("Telemetry");
        if (oldTelemetryRoot.equals(newTelemetryRoot)
                || !Files.isDirectory(oldTelemetryRoot)
                || Files.exists(newTelemetryRoot)) {
            return;
        }
        try {
            Files.createDirectories(newTelemetryRoot.getParent());
            Files.move(oldTelemetryRoot, newTelemetryRoot);
            logInfo(logger, "Migrated embedded telemetry data to " + newTelemetryRoot + ".");
        } catch (Exception moveFailure) {
            try {
                copyDirectory(oldTelemetryRoot, newTelemetryRoot);
                logInfo(logger, "Copied embedded telemetry data to " + newTelemetryRoot + ".");
            } catch (Exception copyFailure) {
                logWarning(logger, "Unable to migrate embedded telemetry data from " + oldTelemetryRoot + " to " + newTelemetryRoot + ".", copyFailure);
            }
        }
    }

    private static void copyDirectory(@Nonnull Path source, @Nonnull Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path sourcePath : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(sourcePath);
                Path targetPath = target.resolve(relative);
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void logInfo(@Nullable HytaleLogger logger, @Nonnull String message) {
        if (logger != null) {
            logger.at(Level.INFO).log(message);
        }
    }

    private static void logWarning(@Nullable HytaleLogger logger,
                                   @Nonnull String message,
                                   @Nonnull Throwable throwable) {
        if (logger != null) {
            logger.at(Level.WARNING).withCause(throwable).log(message);
        }
    }

    interface EmbeddedRuntime {
        boolean isEnabled();

        @Nullable
        String disabledReason();

        void start();

        void shutdown();

        void recordBreadcrumb(@Nonnull String category, @Nonnull String detail);

        void clearBreadcrumbs();

        void captureSetupFailure(@Nullable Throwable throwable);

        void captureStartFailure(@Nullable Throwable throwable);

        void captureExceptionalWorldRemoval(@Nullable World world, @Nullable RemoveWorldEvent.RemovalReason removalReason);

        void recordErrorWithContext(@Nonnull String eventName, @Nullable Throwable throwable, @Nullable TelemetryEventContext context);

        void recordLifecycleWithContext(@Nonnull String eventName, int durationMs, boolean success, @Nullable TelemetryEventContext context);

        void recordPerformanceWithContext(@Nonnull String eventName, int durationMs, @Nullable Double metricValue, @Nullable TelemetryEventContext context);

        void recordUsageWithContext(@Nonnull String eventName, @Nullable TelemetryEventContext context);

        boolean requestFlush();

        @Nonnull
        EmbeddedTelemetryDiagnostics diagnostics();
    }

    private record EmbeddedServiceRuntime(@Nonnull EmbeddedTelemetryService service) implements EmbeddedRuntime {
        @Override
        public boolean isEnabled() {
            return service.isEnabled();
        }

        @Nullable
        @Override
        public String disabledReason() {
            return service.disabledReason();
        }

        @Override
        public void start() {
            service.start();
        }

        @Override
        public void shutdown() {
            service.shutdown();
        }

        @Override
        public void recordBreadcrumb(@Nonnull String category, @Nonnull String detail) {
            service.recordBreadcrumb(category, detail);
        }

        @Override
        public void clearBreadcrumbs() {
            service.clearBreadcrumbs();
        }

        @Override
        public void captureSetupFailure(@Nullable Throwable throwable) {
            service.captureSetupFailure(throwable);
        }

        @Override
        public void captureStartFailure(@Nullable Throwable throwable) {
            service.captureStartFailure(throwable);
        }

        @Override
        public void captureExceptionalWorldRemoval(@Nullable World world, @Nullable RemoveWorldEvent.RemovalReason removalReason) {
            service.captureExceptionalWorldRemoval(world, removalReason);
        }

        @Override
        public void recordErrorWithContext(@Nonnull String eventName, @Nullable Throwable throwable, @Nullable TelemetryEventContext context) {
            service.recordErrorWithContext(eventName, throwable, context);
        }

        @Override
        public void recordLifecycleWithContext(@Nonnull String eventName, int durationMs, boolean success, @Nullable TelemetryEventContext context) {
            service.recordLifecycleWithContext(eventName, durationMs, success, context);
        }

        @Override
        public void recordPerformanceWithContext(@Nonnull String eventName, int durationMs, @Nullable Double metricValue, @Nullable TelemetryEventContext context) {
            service.recordPerformanceWithContext(eventName, durationMs, metricValue, context);
        }

        @Override
        public void recordUsageWithContext(@Nonnull String eventName, @Nullable TelemetryEventContext context) {
            service.recordUsageWithContext(eventName, context);
        }

        @Override
        public boolean requestFlush() {
            return service.requestFlush();
        }

        @Nonnull
        @Override
        public EmbeddedTelemetryDiagnostics diagnostics() {
            return service.diagnostics();
        }
    }

    record FlushSummary(int attempted, int uploaded, int pendingAfter, @Nullable String lastFailure) {
    }
}
