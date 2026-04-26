package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.alechilles.alecstelemetry.core.TelemetryCoreEngine;
import com.alechilles.alecstelemetry.crash.CrashReportClient;
import com.alechilles.alecstelemetry.crash.CrashReportEnvelope;
import com.alechilles.alecstelemetry.crash.HttpCrashReportClient;
import com.alechilles.alecstelemetry.project.TelemetryProjectDescriptor;
import com.alechilles.alecstelemetry.project.TelemetryProjectOverride;
import com.alechilles.alecstelemetry.project.TelemetryProjectRegistration;
import com.alechilles.alecstelemetry.runtime.TelemetryDataPaths;
import com.alechilles.alecstelemetry.runtime.TelemetryProjectOverrideStore;
import com.alechilles.alecstelemetry.runtime.TelemetryRuntimeSettings;
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded telemetry compatibility layer for Tamework.
 *
 * <p>This keeps Tamework's existing settings/debug contract while routing crash,
 * lifecycle, performance, and usage events through the embedded Alec's Telemetry runtime.
 */
public final class CrashTelemetryService {

    private static final String DESCRIPTOR_RESOURCE = "telemetry/project.json";

    private final CrashTelemetrySettings compatibilitySettings;
    private final TelemetryRuntimeSettings runtimeSettings;
    private final TelemetryDataPaths dataPaths;
    private final TelemetryProjectRegistration project;
    private final CrashReportClient client;
    private final HytaleLogger logger;
    private final ScheduledExecutorService executor;
    private final List<CrashReportEnvelope.LoadedModMetadata> loadedMods;
    private final AtomicBoolean enabled;
    private final AtomicBoolean breadcrumbsEnabled;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile TelemetryCoreEngine engine;
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
        TelemetryDataPaths dataPaths = resolveEmbeddedDataPaths(plugin);
        TelemetryRuntimeSettings runtimeSettings = TelemetryRuntimeSettings.load(dataPaths.settingsFile(), logger);
        TelemetryProjectRegistration project = resolveProjectRegistration(plugin, dataPaths, logger);
        CrashReportClient client = new HttpCrashReportClient(
                runtimeSettings.connectTimeoutMs(),
                runtimeSettings.readTimeoutMs(),
                logger
        );
        return new CrashTelemetryService(
                compatibilitySettings,
                runtimeSettings,
                dataPaths,
                project,
                client,
                logger,
                HytaleServer.SCHEDULED_EXECUTOR,
                List.of(new CrashReportEnvelope.LoadedModMetadata(project.pluginIdentifier(), project.pluginVersion()))
        );
    }

    CrashTelemetryService(@Nonnull CrashTelemetrySettings compatibilitySettings,
                          @Nonnull TelemetryRuntimeSettings runtimeSettings,
                          @Nonnull TelemetryDataPaths dataPaths,
                          @Nonnull TelemetryProjectRegistration project,
                          @Nonnull CrashReportClient client,
                          @Nullable HytaleLogger logger,
                          @Nullable ScheduledExecutorService executor,
                          @Nonnull List<CrashReportEnvelope.LoadedModMetadata> loadedMods) {
        this.compatibilitySettings = Objects.requireNonNull(compatibilitySettings, "compatibilitySettings");
        this.runtimeSettings = Objects.requireNonNull(runtimeSettings, "runtimeSettings");
        this.dataPaths = Objects.requireNonNull(dataPaths, "dataPaths");
        this.project = Objects.requireNonNull(project, "project");
        this.client = Objects.requireNonNull(client, "client");
        this.logger = logger;
        this.executor = executor;
        this.loadedMods = List.copyOf(Objects.requireNonNull(loadedMods, "loadedMods"));
        this.enabled = new AtomicBoolean(compatibilitySettings.enabled());
        this.breadcrumbsEnabled = new AtomicBoolean(compatibilitySettings.breadcrumbsEnabled());
        this.engine = createEngine();
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
            engine.recordBreadcrumb(project.projectId(), "lifecycle", "Embedded telemetry started.");
        }
        engine.start();
        syncLastFlushStatus();
    }

    public synchronized void shutdown() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (breadcrumbsEnabled.get()) {
            engine.recordBreadcrumb(project.projectId(), "lifecycle", "Embedded telemetry shutdown.");
        }
        engine.shutdown();
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
                engine.recordBreadcrumb(project.projectId(), "settings", "Embedded telemetry enabled via runtime setting.");
            }
            return;
        }
        if (breadcrumbsEnabled.get()) {
            engine.recordBreadcrumb(project.projectId(), "settings", "Embedded telemetry disabled via runtime setting.");
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
                engine.recordBreadcrumb(project.projectId(), "settings", "Embedded telemetry breadcrumbs enabled via runtime setting.");
            }
            return;
        }
        engine.clearBreadcrumbs(project.projectId());
    }

    public void recordBreadcrumb(@Nonnull String category, @Nonnull String detail) {
        if (!isRuntimeEnabled() || !breadcrumbsEnabled.get()) {
            return;
        }
        engine.recordBreadcrumb(project.projectId(), category, detail);
    }

    public void captureSetupFailure(@Nullable Throwable throwable) {
        if (!isRuntimeEnabled() || throwable == null) {
            return;
        }
        recordBreadcrumb("capture", "Capturing setup failure.");
        engine.captureSetupFailure(project.projectId(), throwable);
        syncLastFlushStatus();
    }

    public void captureStartFailure(@Nullable Throwable throwable) {
        if (!isRuntimeEnabled() || throwable == null) {
            return;
        }
        recordBreadcrumb("capture", "Capturing start failure.");
        engine.captureStartFailure(project.projectId(), throwable);
        syncLastFlushStatus();
    }

    public void captureExceptionalWorldRemoval(@Nullable World world,
                                               @Nullable RemoveWorldEvent.RemovalReason removalReason) {
        if (!isRuntimeEnabled() || world == null || removalReason != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            return;
        }
        recordBreadcrumb("capture", "Capturing exceptional world removal for " + safeWorldName(world) + ".");
        engine.captureExceptionalWorldRemoval(world, removalReason);
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
        boolean recorded = engine.recordError(project.projectId(), eventName, throwable, context);
        syncLastFlushStatus();
        return recorded;
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
        boolean recorded = engine.recordLifecycle(project.projectId(), eventName, durationMs, success, context);
        syncLastFlushStatus();
        return recorded;
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
        engine.recordPerformance(project.projectId(), eventName, durationMs, metricValue, context);
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
        engine.recordUsage(project.projectId(), eventName, context);
        syncLastFlushStatus();
    }

    public boolean triggerFlushAsync() {
        if (!isRuntimeEnabled()) {
            return false;
        }
        recordBreadcrumb("flush", "Manual embedded telemetry flush requested.");
        boolean scheduled = engine.triggerFlushAsync(project.projectId());
        syncLastFlushStatus();
        return scheduled;
    }

    @Nonnull
    public CrashTelemetryDiagnostics diagnostics() {
        syncLastFlushStatus();
        return new CrashTelemetryDiagnostics(
                isRuntimeEnabled(),
                resolveEndpoint(),
                engine.pendingReports(project.projectId()),
                engine.flushInProgress(),
                lastFlushResult,
                lastFlushEpochMs
        );
    }

    @Nonnull
    FlushSummary flushPendingReportsNow(@Nonnull String reason) {
        TelemetryCoreEngine.FlushSummary summary = engine.flushPendingReportsNow(reason, project.projectId());
        syncLastFlushStatus();
        return new FlushSummary(summary.attempted(), summary.uploaded(), summary.pendingAfter(), summary.lastFailure());
    }

    @Nonnull
    private TelemetryCoreEngine createEngine() {
        return new TelemetryCoreEngine(
                runtimeSettings,
                dataPaths,
                List.of(project),
                loadedMods,
                client,
                logger,
                executor
        );
    }

    private boolean isRuntimeEnabled() {
        return enabled.get() && engine.isProjectEnabled(project.projectId());
    }

    private boolean canRecordEvents() {
        if (!isRuntimeEnabled()) {
            return false;
        }
        CrashReportClient.DeliveryTarget target = project.resolveEventDeliveryTarget(runtimeSettings);
        return target != null && !target.endpoint().isBlank();
    }

    private void syncLastFlushStatus() {
        String engineStatus = engine.lastFlushResult();
        if (engineStatus == null || engineStatus.isBlank() || engineStatus.equals(lastFlushResult)) {
            return;
        }
        updateLastFlushStatus(engineStatus);
    }

    private void updateLastFlushStatus(@Nonnull String status) {
        lastFlushResult = status;
        lastFlushEpochMs = System.currentTimeMillis();
    }

    @Nonnull
    private String resolveEndpoint() {
        CrashReportClient.DeliveryTarget target = project.resolveDeliveryTarget(runtimeSettings);
        if (target == null || target.endpoint().isBlank()) {
            return "<disabled>";
        }
        return target.endpoint();
    }

    @Nonnull
    private String disabledReason() {
        if (!enabled.get()) {
            return "Embedded telemetry disabled by Tamework settings.";
        }
        if (!runtimeSettings.enabled()) {
            return "Embedded telemetry disabled by runtime settings.";
        }
        if (!project.isEnabled()) {
            return "Embedded telemetry disabled by project override.";
        }
        return "Embedded telemetry is unavailable.";
    }

    @Nonnull
    private static String safeWorldName(@Nonnull World world) {
        String name = world.getName();
        return name == null || name.isBlank() ? "<unknown-world>" : name.trim();
    }

    @Nonnull
    private static TelemetryDataPaths resolveEmbeddedDataPaths(@Nonnull Tamework plugin) {
        Path telemetryRoot = TameworkSettingsStore.resolveTameworkUniverseRoot(plugin)
                .resolve("Telemetry")
                .toAbsolutePath()
                .normalize();
        Path settingsRoot = telemetryRoot.resolve("Settings");
        return new TelemetryDataPaths(
                telemetryRoot,
                settingsRoot.resolve("runtime.json"),
                settingsRoot.resolve("projects"),
                telemetryRoot,
                telemetryRoot.resolve("crash-reports"),
                telemetryRoot.resolve("events"),
                null
        );
    }

    @Nonnull
    private static TelemetryProjectRegistration resolveProjectRegistration(@Nonnull Tamework plugin,
                                                                           @Nonnull TelemetryDataPaths dataPaths,
                                                                           @Nullable HytaleLogger logger) {
        TelemetryProjectDescriptor descriptor = loadDescriptor(plugin);
        if (!descriptor.isEmbeddedMode()) {
            throw new IllegalStateException(
                    "telemetry/project.json must declare runtimeMode=embedded for Tamework embedded telemetry."
            );
        }
        TelemetryProjectRegistration registration = new TelemetryProjectRegistration(
                descriptor,
                resolvePluginIdentifier(plugin),
                resolvePluginVersion(plugin),
                resolvePluginSourcePath(plugin)
        );
        Map<String, TelemetryProjectOverride> overrides = new TelemetryProjectOverrideStore(logger)
                .loadAll(dataPaths.projectSettingsDirectory());
        TelemetryProjectOverride override = overrides.get(registration.projectId().toLowerCase(Locale.ROOT));
        return override == null ? registration : registration.withOverride(override);
    }

    @Nonnull
    private static TelemetryProjectDescriptor loadDescriptor(@Nonnull Tamework plugin) {
        try (InputStream stream = plugin.getClass().getClassLoader().getResourceAsStream(DESCRIPTOR_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing " + DESCRIPTOR_RESOURCE + " for embedded telemetry.");
            }
            String rawJson = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return TelemetryProjectDescriptor.fromJson(rawJson, buildFallbacks(plugin));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load embedded telemetry descriptor from " + DESCRIPTOR_RESOURCE + ".", ex);
        }
    }

    @Nonnull
    private static TelemetryProjectDescriptor.Fallbacks buildFallbacks(@Nonnull Tamework plugin) {
        String pluginIdentifier = resolvePluginIdentifier(plugin);
        String displayName = resolveDisplayName(plugin, pluginIdentifier);
        String packagePrefix = plugin.getClass().getPackageName();
        return new TelemetryProjectDescriptor.Fallbacks(
                slugify(displayName),
                displayName,
                pluginIdentifier,
                packagePrefix == null || packagePrefix.isBlank() ? List.of() : List.of(packagePrefix)
        );
    }

    @Nonnull
    private static String resolveDisplayName(@Nonnull Tamework plugin, @Nonnull String pluginIdentifier) {
        int separatorIndex = pluginIdentifier.indexOf(':');
        if (separatorIndex >= 0 && separatorIndex < pluginIdentifier.length() - 1) {
            return pluginIdentifier.substring(separatorIndex + 1).trim();
        }
        String className = plugin.getClass().getSimpleName();
        return className == null || className.isBlank() ? "Alec's Tamework!" : className;
    }

    @Nonnull
    private static String resolvePluginIdentifier(@Nonnull Tamework plugin) {
        PluginIdentifier identifier = plugin.getIdentifier();
        if (identifier != null) {
            return identifier.toString();
        }
        PluginManifest manifest = plugin.getManifest();
        if (manifest != null) {
            return new PluginIdentifier(manifest).toString();
        }
        return "Alechilles:Alec's Tamework!";
    }

    @Nonnull
    private static String resolvePluginVersion(@Nonnull Tamework plugin) {
        PluginManifest manifest = plugin.getManifest();
        if (manifest == null) {
            return "unknown";
        }
        Semver version = manifest.getVersion();
        return version == null ? "unknown" : version.toString();
    }

    @Nullable
    private static Path resolvePluginSourcePath(@Nonnull Tamework plugin) {
        try {
            return plugin.getFile() == null ? null : plugin.getFile().toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    private static String slugify(@Nonnull String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean previousDash = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                previousDash = false;
            } else if (c >= 'A' && c <= 'Z') {
                out.append(Character.toLowerCase(c));
                previousDash = false;
            } else if (!previousDash) {
                out.append('-');
                previousDash = true;
            }
        }
        String slug = out.toString();
        while (slug.startsWith("-")) {
            slug = slug.substring(1);
        }
        while (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isBlank() ? "unknown-project" : slug;
    }

    record FlushSummary(int attempted, int uploaded, int pendingAfter, @Nullable String lastFailure) {
    }
}
