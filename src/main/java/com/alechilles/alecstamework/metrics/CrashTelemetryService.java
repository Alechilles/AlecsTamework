package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContext;
import com.alechilles.alecstelemetry.embedded.EmbeddedTelemetryBootstrap;
import com.alechilles.alecstelemetry.embedded.EmbeddedTelemetryDiagnostics;
import com.alechilles.alecstelemetry.embedded.EmbeddedTelemetryService;
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.alechilles.alecstamework.settings.ResolvedTameworkSettings;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Tamework compatibility layer over the standard embedded Alec's Telemetry runtime.
 */
public final class CrashTelemetryService {

    private static final String TAMEWORK_PROJECT_ID = "alecs-tamework";
    private static final String LEGACY_SETTINGS_FILE_NAME = "crash-telemetry.json";

    private final EmbeddedRuntime telemetry;
    private final ExecutorService legacyMigrationExecutor;
    private final AtomicBoolean enabled;
    private final AtomicBoolean breadcrumbsEnabled;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile String lastFlushResult = "No flush attempts yet.";
    private volatile long lastFlushEpochMs;

    @Nonnull
    public static CrashTelemetryService create(@Nonnull Tamework plugin) {
        Objects.requireNonNull(plugin, "plugin");
        HytaleLogger logger = plugin.getLogger();
        Path pluginDataDirectory = plugin.getDataDirectory().toAbsolutePath().normalize();
        Path tameworkUniverseRoot = TameworkSettingsStore.resolveTameworkUniverseRoot(plugin).toAbsolutePath().normalize();
        Path globalSettingsPath = TameworkSettingsStore.resolveGlobalSettingsFile(plugin).toAbsolutePath().normalize();
        TameworkSettingsStore.importLegacyTelemetrySettingsIfMissing(
                globalSettingsPath,
                legacyCrashTelemetrySettingsCandidates(pluginDataDirectory, tameworkUniverseRoot),
                logger
        );
        ResolvedTameworkSettings settings =
                TameworkSettingsStore.loadGlobalSettings(globalSettingsPath, logger);
        boolean telemetryEnabled = settings.telemetryEnabled();
        boolean breadcrumbsEnabled = settings.telemetryBreadcrumbsEnabled();
        EmbeddedTelemetryService embeddedTelemetry = EmbeddedTelemetryBootstrap.bootstrap(plugin);
        CrashTelemetryService service = new CrashTelemetryService(
                telemetryEnabled,
                breadcrumbsEnabled,
                new EmbeddedServiceRuntime(embeddedTelemetry),
                createLegacyMigrationExecutor()
        );
        service.migrateLegacyTelemetryDataAsync(
                pluginDataDirectory.resolve("Telemetry"),
                legacyTelemetryRootCandidates(pluginDataDirectory, tameworkUniverseRoot),
                logger
        );
        return service;
    }

    CrashTelemetryService(boolean enabled,
                          boolean breadcrumbsEnabled,
                          @Nonnull EmbeddedRuntime telemetry) {
        this(enabled, breadcrumbsEnabled, telemetry, createLegacyMigrationExecutor());
    }

    CrashTelemetryService(boolean enabled,
                          boolean breadcrumbsEnabled,
                          @Nonnull EmbeddedRuntime telemetry,
                          @Nonnull ExecutorService legacyMigrationExecutor) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.legacyMigrationExecutor = Objects.requireNonNull(legacyMigrationExecutor, "legacyMigrationExecutor");
        this.enabled = new AtomicBoolean(enabled);
        this.breadcrumbsEnabled = new AtomicBoolean(breadcrumbsEnabled);
        telemetry.setBreadcrumbsEnabled(breadcrumbsEnabled);
        if (!telemetry.setProjectEnabled(true)) {
            updateLastFlushStatus("Failed to persist embedded telemetry project enabled setting.");
        }
        syncLastFlushStatus();
    }

    public synchronized void start() {
        syncLastFlushStatus();
        if (!isEmbeddedRuntimeAvailable()) {
            updateLastFlushStatus(disabledReason());
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (isCrashTelemetryEnabled() && breadcrumbsEnabled.get()) {
            telemetry.recordBreadcrumb("lifecycle", "Embedded telemetry started.");
        }
        telemetry.start();
        syncLastFlushStatus();
    }

    public synchronized void shutdown() {
        legacyMigrationExecutor.shutdownNow();
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (breadcrumbsEnabled.get()) {
            telemetry.recordBreadcrumb("lifecycle", "Embedded telemetry shutdown.");
        }
        telemetry.shutdown();
        syncLastFlushStatus();
    }

    private void migrateLegacyTelemetryDataAsync(@Nonnull Path newTelemetryRoot,
                                                 @Nonnull List<Path> legacyTelemetryRootCandidates,
                                                 @Nullable HytaleLogger logger) {
        legacyMigrationExecutor.execute(() ->
                migrateLegacyTelemetryData(newTelemetryRoot, legacyTelemetryRootCandidates, logger)
        );
    }

    public synchronized void applyEnabledSetting(boolean enabled) {
        boolean previous = this.enabled.getAndSet(enabled);
        if (previous == enabled) {
            return;
        }
        if (enabled) {
            updateLastFlushStatus("Embedded crash telemetry enabled.");
            start();
            if (breadcrumbsEnabled.get()) {
                telemetry.recordBreadcrumb("settings", "Embedded crash telemetry enabled via runtime setting.");
            }
            return;
        }
        updateLastFlushStatus("Embedded crash telemetry disabled by Tamework settings.");
    }

    public synchronized void applyBreadcrumbsEnabledSetting(boolean enabled) {
        boolean previous = this.breadcrumbsEnabled.getAndSet(enabled);
        if (!telemetry.setBreadcrumbsEnabled(enabled)) {
            updateLastFlushStatus("Failed to persist embedded telemetry breadcrumbs setting.");
        }
        if (previous == enabled) {
            return;
        }
        if (enabled) {
            if (isCrashTelemetryEnabled()) {
                telemetry.recordBreadcrumb("settings", "Embedded telemetry breadcrumbs enabled via runtime setting.");
            }
            return;
        }
        telemetry.clearBreadcrumbs();
    }

    public void recordBreadcrumb(@Nonnull String category, @Nonnull String detail) {
        if (!isCrashTelemetryEnabled() || !breadcrumbsEnabled.get()) {
            return;
        }
        telemetry.recordBreadcrumb(category, detail);
    }

    public void recordBreadcrumb(@Nonnull TelemetryBreadcrumbContext context) {
        if (!isCrashTelemetryEnabled() || !breadcrumbsEnabled.get()) return;
        telemetry.recordBreadcrumb(context);
    }

    public void captureSetupFailure(@Nullable Throwable throwable) {
        if (!isCrashTelemetryEnabled() || throwable == null) {
            return;
        }
        recordBreadcrumb("capture", "Capturing setup failure.");
        telemetry.captureSetupFailure(throwable);
        syncLastFlushStatus();
    }

    public void captureStartFailure(@Nullable Throwable throwable) {
        if (!isCrashTelemetryEnabled() || throwable == null) {
            return;
        }
        recordBreadcrumb("capture", "Capturing start failure.");
        telemetry.captureStartFailure(throwable);
        syncLastFlushStatus();
    }

    public void captureExceptionalWorldRemoval(@Nullable World world,
                                               @Nullable RemoveWorldEvent.RemovalReason removalReason) {
        if (!isCrashTelemetryEnabled() || world == null || removalReason != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
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
        if (!isCrashTelemetryEnabled()) {
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
                isCrashTelemetryEnabled(),
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

    private boolean isCrashTelemetryEnabled() {
        return enabled.get() && telemetry.isEnabled();
    }

    private boolean isEmbeddedRuntimeAvailable() {
        return telemetry.isEnabled();
    }

    private boolean canRecordEvents() {
        return isCrashTelemetryEnabled() && !"<disabled>".equals(telemetry.diagnostics().endpoint());
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

    @Nonnull
    static List<Path> legacyCrashTelemetrySettingsCandidates(@Nonnull Path pluginDataDirectory,
                                                             @Nonnull Path tameworkUniverseRoot) {
        return List.of(
                pluginDataDirectory.resolve(LEGACY_SETTINGS_FILE_NAME).toAbsolutePath().normalize(),
                pluginDataDirectory.resolve("Settings").resolve(LEGACY_SETTINGS_FILE_NAME).toAbsolutePath().normalize(),
                pluginDataDirectory.resolve("Telemetry").resolve(LEGACY_SETTINGS_FILE_NAME).toAbsolutePath().normalize(),
                pluginDataDirectory.resolve("telemetry").resolve(LEGACY_SETTINGS_FILE_NAME).toAbsolutePath().normalize(),
                pluginDataDirectory.resolve("telemetry").resolve("Settings").resolve(LEGACY_SETTINGS_FILE_NAME).toAbsolutePath().normalize(),
                pluginDataDirectory.resolve("tamework-crash-telemetry.txt").toAbsolutePath().normalize(),
                tameworkUniverseRoot.resolve("Settings").resolve(LEGACY_SETTINGS_FILE_NAME).toAbsolutePath().normalize(),
                tameworkUniverseRoot.resolve("Telemetry").resolve(LEGACY_SETTINGS_FILE_NAME).toAbsolutePath().normalize(),
                tameworkUniverseRoot.resolve("telemetry").resolve(LEGACY_SETTINGS_FILE_NAME).toAbsolutePath().normalize(),
                tameworkUniverseRoot.resolve("tamework-crash-telemetry.txt").toAbsolutePath().normalize(),
                tameworkUniverseRoot.resolve(LEGACY_SETTINGS_FILE_NAME).toAbsolutePath().normalize()
        );
    }

    @Nonnull
    static List<Path> legacyTelemetryRootCandidates(@Nonnull Path pluginDataDirectory,
                                                   @Nonnull Path tameworkUniverseRoot) {
        return List.of(
                tameworkUniverseRoot.resolve("Telemetry").toAbsolutePath().normalize(),
                tameworkUniverseRoot.resolve("telemetry").toAbsolutePath().normalize(),
                pluginDataDirectory.resolve("telemetry").toAbsolutePath().normalize()
        );
    }

    static void migrateLegacyTelemetryData(@Nonnull Path newTelemetryRoot,
                                           @Nonnull List<Path> legacyTelemetryRootCandidates,
                                           @Nullable HytaleLogger logger) {
        Path targetRoot = newTelemetryRoot.toAbsolutePath().normalize();
        boolean targetHasData = isNonEmptyDirectory(targetRoot);
        boolean hasSameLegacyRoot = legacyTelemetryRootCandidates.stream()
                .map(candidate -> candidate.toAbsolutePath().normalize())
                .anyMatch(candidate -> Files.isDirectory(candidate)
                        && (candidate.equals(targetRoot) || isSamePath(candidate, targetRoot)));
        if (targetHasData && !hasSameLegacyRoot) {
            return;
        }
        for (Path candidate : legacyTelemetryRootCandidates) {
            Path sourceRoot = candidate.toAbsolutePath().normalize();
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            copyLegacyTelemetryArtifacts(sourceRoot, targetRoot, isPluginLocalLegacyRoot(sourceRoot, targetRoot), logger);
        }
    }

    private static boolean copyLegacyTelemetryArtifacts(@Nonnull Path oldTelemetryRoot,
                                                        @Nonnull Path newTelemetryRoot,
                                                        boolean includeUnprojectedQueues,
                                                        @Nullable HytaleLogger logger) {
        try {
            Path parent = newTelemetryRoot.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            int copied = 0;
            copied += copyDirectoryIfExists(
                    oldTelemetryRoot.resolve("crash-reports").resolve(TAMEWORK_PROJECT_ID),
                    newTelemetryRoot.resolve("crash-reports").resolve(TAMEWORK_PROJECT_ID)
            );
            copied += copyDirectoryIfExists(
                    oldTelemetryRoot.resolve("events").resolve(TAMEWORK_PROJECT_ID),
                    newTelemetryRoot.resolve("events").resolve(TAMEWORK_PROJECT_ID)
            );
            if (includeUnprojectedQueues) {
                copied += copyDirectoryIfExists(
                        oldTelemetryRoot.resolve("crash-reports").resolve("pending"),
                        newTelemetryRoot.resolve("crash-reports").resolve(TAMEWORK_PROJECT_ID).resolve("pending")
                );
                copied += copyDirectoryIfExists(
                        oldTelemetryRoot.resolve("events").resolve("pending"),
                        newTelemetryRoot.resolve("events").resolve(TAMEWORK_PROJECT_ID).resolve("pending")
                );
            }
            copied += copyFileIfExists(
                    oldTelemetryRoot.resolve("Settings").resolve("runtime.json"),
                    newTelemetryRoot.resolve("Settings").resolve("runtime.json")
            );
            copied += copyFileIfExists(
                    oldTelemetryRoot.resolve("Settings").resolve("server-id.txt"),
                    newTelemetryRoot.resolve("Settings").resolve("server-id.txt")
            );
            copied += copyFileIfExists(
                    oldTelemetryRoot.resolve("server-id.txt"),
                    newTelemetryRoot.resolve("Settings").resolve("server-id.txt")
            );
            copied += copyFileIfExists(
                    oldTelemetryRoot.resolve("Settings").resolve(LEGACY_SETTINGS_FILE_NAME),
                    newTelemetryRoot.resolve("Settings").resolve(LEGACY_SETTINGS_FILE_NAME)
            );
            copied += copyFileIfExists(
                    oldTelemetryRoot.resolve("Settings").resolve("projects").resolve(TAMEWORK_PROJECT_ID + ".json"),
                    newTelemetryRoot.resolve("Settings").resolve("projects").resolve(TAMEWORK_PROJECT_ID + ".json")
            );
            if (copied > 0) {
                logInfo(logger, "Copied embedded telemetry data to " + newTelemetryRoot + ".");
                return true;
            }
            return false;
        } catch (Exception copyFailure) {
            logWarning(logger, "Unable to migrate embedded telemetry data from " + oldTelemetryRoot + " to " + newTelemetryRoot + ".", copyFailure);
            return false;
        }
    }

    private static boolean isPluginLocalLegacyRoot(@Nonnull Path sourceRoot, @Nonnull Path targetRoot) {
        if (sourceRoot.equals(targetRoot) || isSamePath(sourceRoot, targetRoot)) {
            return true;
        }
        Path sourceParent = sourceRoot.getParent();
        Path targetParent = targetRoot.getParent();
        Path sourceFileName = sourceRoot.getFileName();
        return sourceParent != null
                && targetParent != null
                && sourceFileName != null
                && "telemetry".equalsIgnoreCase(sourceFileName.toString())
                && (sourceParent.equals(targetParent) || isSamePath(sourceParent, targetParent));
    }

    private static int copyDirectoryIfExists(@Nonnull Path source, @Nonnull Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return 0;
        }
        int copied = 0;
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path sourcePath : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(sourcePath);
                Path targetPath = target.resolve(relative);
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else if (!Files.exists(targetPath)) {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES);
                    copied++;
                }
            }
        }
        return copied;
    }

    private static int copyFileIfExists(@Nonnull Path source, @Nonnull Path target) throws IOException {
        if (!Files.isRegularFile(source) || Files.exists(target)) {
            return 0;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        return 1;
    }

    private static boolean isNonEmptyDirectory(@Nonnull Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.findAny().isPresent();
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean isSamePath(@Nonnull Path first, @Nonnull Path second) {
        try {
            return Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
        } catch (Exception ignored) {
            return false;
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

    @Nonnull
    private static ExecutorService createLegacyMigrationExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AlecTamework-CrashTelemetryMigration");
            thread.setDaemon(true);
            return thread;
        });
    }

    interface EmbeddedRuntime {
        boolean isEnabled();

        @Nullable
        String disabledReason();

        void start();

        void shutdown();

        void recordBreadcrumb(@Nonnull String category, @Nonnull String detail);

        default void recordBreadcrumb(@Nonnull TelemetryBreadcrumbContext context) {
            TelemetryBreadcrumbContext normalized = context.normalize();
            recordBreadcrumb(normalized.category(), normalized.detail());
        }

        void clearBreadcrumbs();

        boolean setProjectEnabled(boolean enabled);

        boolean setBreadcrumbsEnabled(boolean enabled);

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
        public void recordBreadcrumb(@Nonnull TelemetryBreadcrumbContext context) {
            service.recordBreadcrumb(context);
        }

        @Override
        public void clearBreadcrumbs() {
            service.clearBreadcrumbs();
        }

        @Override
        public boolean setProjectEnabled(boolean enabled) {
            return service.setProjectEnabled(enabled);
        }

        @Override
        public boolean setBreadcrumbsEnabled(boolean enabled) {
            return service.setBreadcrumbsEnabled(enabled);
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
