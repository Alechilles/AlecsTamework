package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstamework.persistence.TameworkDataPathService;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Store-first crash telemetry service for Tamework fatal failures.
 */
public final class CrashTelemetryService {

    private static final long PERIODIC_FLUSH_INTERVAL_SECONDS = 180L;
    private static final String SETTINGS_DIRECTORY_NAME = "Settings";
    private static final String TELEMETRY_DIRECTORY_NAME = "Telemetry";
    private static final String LEGACY_SETTINGS_FILE_NAME = "tamework-crash-telemetry.txt";
    private static final String LEGACY_TELEMETRY_DIRECTORY_NAME = "telemetry";
    private static final String CRASH_REPORTS_DIRECTORY_NAME = "crash-reports";
    private static final String PENDING_DIRECTORY_NAME = "pending";

    private static final String SOURCE_UNCAUGHT_EXCEPTION = "uncaught_exception";
    private static final String SOURCE_EXCEPTIONAL_WORLD_REMOVAL = "exceptional_world_removal";
    private static final String SOURCE_SETUP_FAILURE = "plugin_setup_failure";
    private static final String SOURCE_START_FAILURE = "plugin_start_failure";

    private final CrashTelemetrySettings settings;
    private final CrashReportStore store;
    private final CrashReportClient client;
    private final PluginIdentifier pluginIdentifier;
    private final String pluginVersion;
    private final HytaleLogger logger;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean enabled;
    private final JavaPlugin runtimePlugin;
    private final CrashBreadcrumbBuffer breadcrumbs;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean uncaughtHandlerInstalled = new AtomicBoolean(false);
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);

    private volatile Thread.UncaughtExceptionHandler previousUncaughtHandler;
    private volatile Thread.UncaughtExceptionHandler installedUncaughtHandler;
    private volatile ScheduledFuture<?> periodicFlushFuture;
    private volatile String lastFlushResult = "No flush attempts yet.";
    private volatile long lastFlushEpochMs;

    @Nonnull
    public static CrashTelemetryService create(@Nonnull JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        HytaleLogger logger = plugin.getLogger();
        Path legacyDataDirectory = plugin.getDataDirectory().toAbsolutePath().normalize();
        Path tameworkUniverseDirectory = resolveTameworkUniverseDirectory(legacyDataDirectory, logger);
        Path telemetryDirectory = tameworkUniverseDirectory.resolve(TELEMETRY_DIRECTORY_NAME);
        Path settingsFile = resolveSettingsFile(legacyDataDirectory, tameworkUniverseDirectory, logger);
        CrashTelemetrySettings settings = CrashTelemetrySettings.load(settingsFile, logger);
        Path pendingDirectory = telemetryDirectory
                .resolve(CRASH_REPORTS_DIRECTORY_NAME)
                .resolve(PENDING_DIRECTORY_NAME);
        Path legacyPendingDirectory = legacyDataDirectory
                .resolve(LEGACY_TELEMETRY_DIRECTORY_NAME)
                .resolve(CRASH_REPORTS_DIRECTORY_NAME)
                .resolve(PENDING_DIRECTORY_NAME);
        migrateLegacyPendingReports(legacyPendingDirectory, pendingDirectory, logger);
        CrashReportStore store = new CrashReportStore(pendingDirectory, settings.maxPendingReports(), logger);

        CrashReportClient client = new HttpCrashReportClient(
                settings.endpoint(),
                settings.connectTimeoutMs(),
                settings.readTimeoutMs(),
                logger
        );

        PluginIdentifier pluginIdentifier = resolvePluginIdentifier(plugin);
        String pluginVersion = resolvePluginVersion(plugin);

        return new CrashTelemetryService(
                settings,
                store,
                client,
                pluginIdentifier,
                pluginVersion,
                logger,
                HytaleServer.SCHEDULED_EXECUTOR,
                plugin
        );
    }

    CrashTelemetryService(@Nonnull CrashTelemetrySettings settings,
                          @Nonnull CrashReportStore store,
                          @Nullable CrashReportClient client,
                          @Nonnull PluginIdentifier pluginIdentifier,
                          @Nonnull String pluginVersion,
                          @Nullable HytaleLogger logger,
                          @Nullable ScheduledExecutorService executor) {
        this(
                settings,
                store,
                client,
                pluginIdentifier,
                pluginVersion,
                logger,
                executor,
                null
        );
    }

    CrashTelemetryService(@Nonnull CrashTelemetrySettings settings,
                          @Nonnull CrashReportStore store,
                          @Nullable CrashReportClient client,
                          @Nonnull PluginIdentifier pluginIdentifier,
                          @Nonnull String pluginVersion,
                          @Nullable HytaleLogger logger,
                          @Nullable ScheduledExecutorService executor,
                          @Nullable JavaPlugin runtimePlugin) {
        this.settings = settings;
        this.store = store;
        this.client = client;
        this.pluginIdentifier = pluginIdentifier;
        this.pluginVersion = pluginVersion;
        this.logger = logger;
        this.executor = executor;
        this.enabled = new AtomicBoolean(settings.enabled());
        this.runtimePlugin = runtimePlugin;
        this.breadcrumbs = new CrashBreadcrumbBuffer(
                settings.breadcrumbsEnabled(),
                settings.breadcrumbsCapacity()
        );
    }

    public void start() {
        if (!isEnabled()) {
            lastFlushResult = "Crash telemetry disabled by settings.";
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        recordBreadcrumb("lifecycle", "Crash telemetry started.");
        installUncaughtExceptionHandler();
        requestFlushAsync("startup");

        if (executor != null) {
            periodicFlushFuture = executor.scheduleWithFixedDelay(
                    () -> requestFlushAsync("periodic"),
                    PERIODIC_FLUSH_INTERVAL_SECONDS,
                    PERIODIC_FLUSH_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );
        }
    }

    public void shutdown() {
        ScheduledFuture<?> scheduledFuture = periodicFlushFuture;
        periodicFlushFuture = null;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        uninstallUncaughtExceptionHandler();
        started.set(false);
        recordBreadcrumb("lifecycle", "Crash telemetry shutdown.");
    }

    public void applyEnabledSetting(boolean enabled) {
        boolean previous = this.enabled.getAndSet(enabled);
        if (previous == enabled) {
            return;
        }
        if (enabled) {
            lastFlushResult = "Crash telemetry enabled.";
            recordBreadcrumb("settings", "Crash telemetry enabled via runtime setting.");
            start();
            return;
        }
        shutdown();
        lastFlushResult = "Crash telemetry disabled by settings.";
        recordBreadcrumb("settings", "Crash telemetry disabled via runtime setting.");
    }

    public void applyBreadcrumbsEnabledSetting(boolean enabled) {
        if (!breadcrumbs.setEnabled(enabled)) {
            return;
        }
        if (enabled) {
            recordBreadcrumb("settings", "Crash telemetry breadcrumbs enabled via runtime setting.");
        }
    }

    public void captureSetupFailure(@Nullable Throwable throwable) {
        recordBreadcrumb("capture", "Capturing setup failure.");
        captureThrowable(SOURCE_SETUP_FAILURE, throwable, Thread.currentThread(), null, null, null);
    }

    public void captureStartFailure(@Nullable Throwable throwable) {
        recordBreadcrumb("capture", "Capturing start failure.");
        captureThrowable(SOURCE_START_FAILURE, throwable, Thread.currentThread(), null, null, null);
    }

    public void captureExceptionalWorldRemoval(@Nullable World world,
                                               @Nullable RemoveWorldEvent.RemovalReason removalReason) {
        if (world == null || removalReason != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            return;
        }
        recordBreadcrumb(
                "capture",
                "Capturing exceptional world removal for " + normalizeValue(world.getName(), "<unknown-world>") + "."
        );
        captureThrowable(
                SOURCE_EXCEPTIONAL_WORLD_REMOVAL,
                world.getFailureException(),
                Thread.currentThread(),
                world.getName(),
                removalReason.name(),
                world.getPossibleFailureCause()
        );
    }

    public boolean triggerFlushAsync() {
        recordBreadcrumb("flush", "Manual crash telemetry flush requested.");
        return requestFlushAsync("manual");
    }

    @Nonnull
    public CrashTelemetryDiagnostics diagnostics() {
        int pending = store.pendingCount();
        return new CrashTelemetryDiagnostics(
                isEnabled(),
                settings.endpoint(),
                pending,
                flushInProgress.get(),
                lastFlushResult,
                lastFlushEpochMs
        );
    }

    @Nonnull
    FlushSummary flushPendingReportsNow(@Nonnull String reason) {
        if (!isEnabled()) {
            FlushSummary summary = new FlushSummary(0, 0, store.pendingCount(), "disabled");
            updateFlushStatus(reason, summary, null);
            return summary;
        }
        if (client == null) {
            FlushSummary summary = new FlushSummary(0, 0, store.pendingCount(), "no_endpoint");
            updateFlushStatus(reason, summary, "Endpoint is not configured.");
            return summary;
        }

        try {
            int attempted = 0;
            int uploaded = 0;
            String lastFailure = null;
            for (CrashReportStore.PendingReport pending : store.listPendingReports(settings.maxUploadsPerFlush())) {
                attempted++;
                CrashReportClient.UploadResult uploadResult = client.upload(pending.payload());
                if (uploadResult.success()) {
                    if (store.delete(pending.path())) {
                        uploaded++;
                    } else {
                        lastFailure = "Uploaded but failed to remove local file " + pending.path().getFileName();
                    }
                } else {
                    lastFailure = uploadResult.detail() == null
                            ? "HTTP status " + uploadResult.statusCode()
                            : uploadResult.detail();
                }
            }
            FlushSummary summary = new FlushSummary(attempted, uploaded, store.pendingCount(), lastFailure);
            updateFlushStatus(reason, summary, lastFailure);
            recordBreadcrumb(
                    "flush",
                    "Flush complete (reason=" + reason
                            + ", attempted=" + attempted
                            + ", uploaded=" + uploaded
                            + ", pending=" + summary.pendingAfter() + ")."
            );
            return summary;
        } catch (Exception ex) {
            FlushSummary summary = new FlushSummary(0, 0, store.pendingCount(), ex.getMessage());
            updateFlushStatus(reason, summary, ex.getMessage());
            logWarning("Crash telemetry flush pass failed.", ex);
            return summary;
        }
    }

    private boolean requestFlushAsync(@Nonnull String reason) {
        if (!isEnabled()) {
            return false;
        }
        if (executor == null) {
            return false;
        }
        if (!flushInProgress.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    flushPendingReportsNow(reason);
                } finally {
                    flushInProgress.set(false);
                }
            });
            return true;
        } catch (Exception ex) {
            flushInProgress.set(false);
            logWarning("Crash telemetry flush scheduling failed.", ex);
            return false;
        }
    }

    private void installUncaughtExceptionHandler() {
        if (!isEnabled()) {
            return;
        }
        if (!uncaughtHandlerInstalled.compareAndSet(false, true)) {
            return;
        }

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
            try {
                captureThrowable(
                        SOURCE_UNCAUGHT_EXCEPTION,
                        throwable,
                        thread,
                        null,
                        null,
                        null
                );
            } catch (Exception captureFailure) {
                logWarning("Crash telemetry uncaught handler capture failed.", captureFailure);
            }

            if (previous != null) {
                try {
                    previous.uncaughtException(thread, throwable);
                } catch (Exception delegateFailure) {
                    logWarning("Delegated uncaught exception handler failed.", delegateFailure);
                }
            }
        };

        previousUncaughtHandler = previous;
        installedUncaughtHandler = handler;
        Thread.setDefaultUncaughtExceptionHandler(handler);
        recordBreadcrumb("lifecycle", "Installed uncaught exception handler for crash telemetry.");
    }

    private void uninstallUncaughtExceptionHandler() {
        if (!uncaughtHandlerInstalled.compareAndSet(true, false)) {
            return;
        }
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current == installedUncaughtHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler);
        }
        installedUncaughtHandler = null;
        previousUncaughtHandler = null;
        recordBreadcrumb("lifecycle", "Uninstalled uncaught exception handler for crash telemetry.");
    }

    private void captureThrowable(@Nonnull String source,
                                  @Nullable Throwable throwable,
                                  @Nullable Thread thread,
                                  @Nullable String worldName,
                                  @Nullable String worldRemovalReason,
                                  @Nullable PluginIdentifier worldFailurePluginIdentifier) {
        if (!isEnabled() || throwable == null) {
            return;
        }
        try {
            CrashAttribution.AttributionResult attribution = CrashAttribution.classify(throwable, pluginIdentifier);
            if (!attribution.attributed()) {
                recordBreadcrumb("capture", "Skipped non-attributed throwable from source " + source + ".");
                return;
            }

            String threadName = thread == null ? Thread.currentThread().getName() : thread.getName();
            recordBreadcrumb(
                    "capture",
                    "Captured attributed throwable from source " + source + " on thread " + threadName + "."
            );
            CrashReportEnvelope envelope = CrashReportEnvelope.create(
                    source,
                    attribution.fingerprint(),
                    pluginIdentifier.toString(),
                    pluginVersion,
                    threadName,
                    worldName,
                    worldRemovalReason,
                    worldFailurePluginIdentifier == null ? null : worldFailurePluginIdentifier.toString(),
                    attribution,
                    throwable,
                    CrashReportEnvelope.RuntimeMetadata.capture(runtimePlugin),
                    breadcrumbs.snapshot()
            );

            CrashReportStore.WriteResult writeResult = store.persist(envelope);
            if (writeResult == CrashReportStore.WriteResult.FAILED) {
                logWarning("Failed to store crash telemetry report.", null);
            } else if (writeResult == CrashReportStore.WriteResult.UPDATED) {
                recordBreadcrumb("store", "Aggregated duplicate crash fingerprint " + attribution.fingerprint() + ".");
            }
        } catch (Throwable captureFailure) {
            logWarning("Crash telemetry capture failed.", captureFailure);
        }
    }

    private void updateFlushStatus(@Nonnull String reason,
                                   @Nonnull FlushSummary summary,
                                   @Nullable String detail) {
        lastFlushEpochMs = System.currentTimeMillis();
        lastFlushResult = "reason=" + reason
                + ", attempted=" + summary.attempted()
                + ", uploaded=" + summary.uploaded()
                + ", pending=" + summary.pendingAfter()
                + (detail == null || detail.isBlank() ? "" : ", detail=" + detail);
    }

    private void logWarning(@Nonnull String message, @Nullable Throwable throwable) {
        if (logger == null) {
            return;
        }
        if (throwable == null) {
            logger.at(Level.WARNING).log(message);
            return;
        }
        logger.at(Level.WARNING).withCause(throwable).log(message);
    }

    private boolean isEnabled() {
        return enabled.get();
    }

    private void recordBreadcrumb(@Nonnull String category, @Nonnull String detail) {
        breadcrumbs.record(category, detail);
    }

    @Nonnull
    private static String normalizeValue(@Nullable String value, @Nonnull String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    @Nonnull
    private static Path resolveTameworkUniverseDirectory(@Nonnull Path legacyDataDirectory,
                                                         @Nullable HytaleLogger logger) {
        Path resolvedDataDirectory = new TameworkDataPathService(logger)
                .resolveAndMigrateDataDirectory(legacyDataDirectory);
        Path tameworkUniverseDirectory = resolvedDataDirectory.getParent();
        return tameworkUniverseDirectory == null ? legacyDataDirectory : tameworkUniverseDirectory;
    }

    @Nonnull
    private static Path resolveSettingsFile(@Nonnull Path legacyDataDirectory,
                                            @Nonnull Path tameworkUniverseDirectory,
                                            @Nullable HytaleLogger logger) {
        Path settingsDirectory = tameworkUniverseDirectory.resolve(SETTINGS_DIRECTORY_NAME);
        Path targetSettingsFile = settingsDirectory.resolve(CrashTelemetrySettings.FILE_NAME);
        if (Files.isRegularFile(targetSettingsFile)) {
            return targetSettingsFile;
        }

        Path telemetryDirectory = tameworkUniverseDirectory.resolve(TELEMETRY_DIRECTORY_NAME);
        Path legacySettingsFile = firstExisting(
                legacyDataDirectory.resolve(CrashTelemetrySettings.FILE_NAME),
                telemetryDirectory.resolve(CrashTelemetrySettings.FILE_NAME),
                legacyDataDirectory.resolve(LEGACY_SETTINGS_FILE_NAME),
                telemetryDirectory.resolve(LEGACY_SETTINGS_FILE_NAME)
        );
        if (legacySettingsFile == null) {
            return targetSettingsFile;
        }
        try {
            Files.createDirectories(settingsDirectory);
            Files.copy(legacySettingsFile, targetSettingsFile);
            return targetSettingsFile;
        } catch (Exception ex) {
            logWarning(
                    logger,
                    "Failed to copy legacy crash telemetry settings to " + targetSettingsFile + "; using legacy path.",
                    ex
            );
            return legacySettingsFile;
        }
    }

    @Nullable
    private static Path firstExisting(@Nonnull Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void migrateLegacyPendingReports(@Nonnull Path legacyPendingDirectory,
                                                    @Nonnull Path pendingDirectory,
                                                    @Nullable HytaleLogger logger) {
        if (legacyPendingDirectory.equals(pendingDirectory) || !Files.isDirectory(legacyPendingDirectory)) {
            return;
        }
        try {
            Files.createDirectories(pendingDirectory);
            int movedCount = 0;
            try (var stream = Files.list(legacyPendingDirectory)) {
                for (Path source : stream.toList()) {
                    if (!Files.isRegularFile(source)) {
                        continue;
                    }
                    String fileName = source.getFileName() == null ? "" : source.getFileName().toString();
                    if (!fileName.endsWith(".json")) {
                        continue;
                    }
                    Path destination = pendingDirectory.resolve(fileName);
                    if (Files.exists(destination)) {
                        continue;
                    }
                    Files.move(source, destination);
                    movedCount++;
                }
            }
            if (movedCount > 0) {
                logWarning(
                        logger,
                        "Migrated "
                                + movedCount
                                + " crash telemetry report(s) from "
                                + legacyPendingDirectory
                                + " to "
                                + pendingDirectory
                                + ".",
                        null
                );
            }
        } catch (Exception ex) {
            logWarning(
                    logger,
                    "Failed to migrate legacy crash telemetry reports from "
                            + legacyPendingDirectory
                            + " to "
                            + pendingDirectory
                            + ".",
                    ex
            );
        }
    }

    private static void logWarning(@Nullable HytaleLogger logger,
                                   @Nonnull String message,
                                   @Nullable Throwable throwable) {
        if (logger == null) {
            return;
        }
        if (throwable == null) {
            logger.at(Level.WARNING).log(message);
            return;
        }
        logger.at(Level.WARNING).withCause(throwable).log(message);
    }

    @Nonnull
    private static PluginIdentifier resolvePluginIdentifier(@Nonnull JavaPlugin plugin) {
        PluginIdentifier identifier = plugin.getIdentifier();
        if (identifier != null) {
            return identifier;
        }
        PluginManifest manifest = plugin.getManifest();
        if (manifest != null) {
            return new PluginIdentifier(manifest);
        }
        return new PluginIdentifier("Alechilles", "Alec's Tamework!");
    }

    @Nonnull
    private static String resolvePluginVersion(@Nonnull JavaPlugin plugin) {
        PluginManifest manifest = plugin.getManifest();
        if (manifest == null) {
            return "Unknown";
        }
        Semver version = manifest.getVersion();
        if (version == null) {
            return "Unknown";
        }
        return version.toString();
    }

    record FlushSummary(int attempted, int uploaded, int pendingAfter, @Nullable String lastFailure) {
    }

    private static final class CrashBreadcrumbBuffer {
        private boolean enabled;
        private final int capacity;
        private final ArrayDeque<CrashReportEnvelope.Breadcrumb> buffer;

        private CrashBreadcrumbBuffer(boolean enabled, int capacity) {
            this.enabled = enabled;
            this.capacity = Math.max(1, capacity);
            this.buffer = new ArrayDeque<>(this.capacity);
        }

        private synchronized void record(@Nonnull String category, @Nonnull String detail) {
            if (!enabled) {
                return;
            }
            if (buffer.size() >= capacity) {
                buffer.removeFirst();
            }
            buffer.addLast(new CrashReportEnvelope.Breadcrumb(
                    Instant.now().toString(),
                    category,
                    detail
            ));
        }

        @Nonnull
        private synchronized List<CrashReportEnvelope.Breadcrumb> snapshot() {
            if (!enabled || buffer.isEmpty()) {
                return List.of();
            }
            return List.copyOf(new ArrayList<>(buffer));
        }

        private synchronized boolean setEnabled(boolean enabled) {
            if (this.enabled == enabled) {
                return false;
            }
            this.enabled = enabled;
            if (!enabled) {
                buffer.clear();
            }
            return true;
        }
    }
}
