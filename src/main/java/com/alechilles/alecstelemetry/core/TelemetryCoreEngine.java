package com.alechilles.alecstelemetry.core;

import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.alechilles.alecstelemetry.crash.CrashAttribution;
import com.alechilles.alecstelemetry.crash.CrashReportClient;
import com.alechilles.alecstelemetry.crash.CrashReportEnvelope;
import com.alechilles.alecstelemetry.crash.CrashReportStore;
import com.alechilles.alecstelemetry.event.TelemetryEventEnvelope;
import com.alechilles.alecstelemetry.event.TelemetryEventStore;
import com.alechilles.alecstelemetry.project.TelemetryProjectRegistration;
import com.alechilles.alecstelemetry.runtime.TelemetryBreadcrumbBuffer;
import com.alechilles.alecstelemetry.runtime.TelemetryDataPaths;
import com.alechilles.alecstelemetry.runtime.TelemetryRuntimeSettings;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Shared crash telemetry engine used by standalone and embedded telemetry modes.
 */
public final class TelemetryCoreEngine {

    private static final String SOURCE_UNCAUGHT_EXCEPTION = "uncaught_exception";
    private static final String SOURCE_EXCEPTIONAL_WORLD_REMOVAL = "exceptional_world_removal";
    private static final String SOURCE_SETUP_FAILURE = "plugin_setup_failure";
    private static final String SOURCE_START_FAILURE = "plugin_start_failure";
    private static final String SOURCE_MANUAL_TEST = "manual_test";

    private final TelemetryRuntimeSettings settings;
    private final TelemetryDataPaths dataPaths;
    private final List<TelemetryProjectRegistration> projects;
    private final List<CrashReportEnvelope.LoadedModMetadata> loadedMods;
    private final CrashReportClient client;
    private final HytaleLogger logger;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean enabled;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean uncaughtHandlerInstalled = new AtomicBoolean(false);
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);
    private final LinkedHashMap<String, CrashReportStore> storesByProjectId = new LinkedHashMap<>();
    private final LinkedHashMap<String, TelemetryEventStore> eventStoresByProjectId = new LinkedHashMap<>();
    private final LinkedHashMap<String, CrashReportEnvelope.EnvironmentSnapshot> environmentsByProjectId = new LinkedHashMap<>();
    private final TelemetryBreadcrumbBuffer breadcrumbs;
    private final String sessionId = UUID.randomUUID().toString();

    private volatile Thread.UncaughtExceptionHandler previousUncaughtHandler;
    private volatile TelemetryUncaughtExceptionHandler installedUncaughtHandler;
    private volatile ScheduledFuture<?> periodicFlushFuture;
    private volatile String lastFlushResult = "No flush attempts yet.";

    public TelemetryCoreEngine(@Nonnull TelemetryRuntimeSettings settings,
                               @Nonnull TelemetryDataPaths dataPaths,
                               @Nonnull List<TelemetryProjectRegistration> projects,
                               @Nonnull List<CrashReportEnvelope.LoadedModMetadata> loadedMods,
                               @Nonnull CrashReportClient client,
                               @Nullable HytaleLogger logger,
                               @Nullable ScheduledExecutorService executor) {
        this.settings = settings;
        this.dataPaths = dataPaths;
        this.projects = List.copyOf(projects);
        this.loadedMods = List.copyOf(loadedMods);
        this.client = client;
        this.logger = logger;
        this.executor = executor;
        this.enabled = new AtomicBoolean(settings.enabled());
        this.breadcrumbs = new TelemetryBreadcrumbBuffer(settings.maxBreadcrumbsPerProject());
    }

    public void start() {
        if (!enabled.get() || projects.isEmpty()) {
            lastFlushResult = projects.isEmpty() ? "No registered telemetry projects discovered." : "Runtime disabled by settings.";
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        installUncaughtExceptionHandler();
        requestFlushAsync("startup", null);
        if (executor != null) {
            periodicFlushFuture = executor.scheduleWithFixedDelay(
                    () -> requestFlushAsync("periodic", null),
                    settings.flushIntervalSeconds(),
                    settings.flushIntervalSeconds(),
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
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    @Nonnull
    public List<TelemetryProjectRegistration> projects() {
        return projects;
    }

    @Nonnull
    public List<CrashReportEnvelope.LoadedModMetadata> loadedMods() {
        return loadedMods;
    }

    @Nonnull
    public String lastFlushResult() {
        return lastFlushResult;
    }

    public boolean flushInProgress() {
        return flushInProgress.get();
    }

    public boolean isProjectEnabled(@Nonnull String projectId) {
        TelemetryProjectRegistration project = findProject(projectId);
        return project != null && project.isEnabled();
    }

    public boolean triggerFlushAsync() {
        return requestFlushAsync("manual", null);
    }

    public boolean triggerFlushAsync(@Nullable String projectId) {
        return requestFlushAsync("manual", projectId);
    }

    public void recordBreadcrumb(@Nonnull String projectId,
                                 @Nonnull String category,
                                 @Nonnull String detail) {
        if (!enabled.get()) {
            return;
        }
        TelemetryProjectRegistration project = findProject(projectId);
        if (project == null || !project.isEnabled()) {
            return;
        }
        if (!project.events().breadcrumbs().enabled()) {
            return;
        }
        breadcrumbs.record(project.projectId(), category, detail);
    }

    public void clearBreadcrumbs(@Nonnull String projectId) {
        TelemetryProjectRegistration project = findProject(projectId);
        breadcrumbs.clear(project == null ? projectId : project.projectId());
    }

    public void captureSetupFailure(@Nonnull String projectId, @Nullable Throwable throwable) {
        captureExplicitProject(projectId, SOURCE_SETUP_FAILURE, throwable);
    }

    public void captureStartFailure(@Nonnull String projectId, @Nullable Throwable throwable) {
        captureExplicitProject(projectId, SOURCE_START_FAILURE, throwable);
    }

    public void recordError(@Nonnull String projectId,
                            @Nonnull String eventName,
                            @Nullable Throwable throwable,
                            @Nullable String detail) {
        recordError(projectId, eventName, throwable, TelemetryEventContext.builder().detail(detail).build());
    }

    public void recordError(@Nonnull String projectId,
                            @Nonnull String eventName,
                            @Nullable Throwable throwable,
                            @Nullable TelemetryEventContext context) {
        if (!enabled.get()) {
            return;
        }
        TelemetryProjectRegistration project = findProject(projectId);
        if (project == null
                || !project.isEnabled()
                || !project.events().errors().enabled()
                || project.resolveEventDeliveryTarget(settings) == null) {
            return;
        }
        TelemetryEventContext normalizedContext = normalizeContext(context);
        CrashReportEnvelope.RuntimeMetadata runtimeMetadata = CrashReportEnvelope.RuntimeMetadata.capture(loadedMods);
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        putDetail(attributes, normalizedContext);
        if (throwable != null) {
            attributes.put("throwableType", throwable.getClass().getName());
            attributes.put("throwableMessage", throwable.getMessage() == null ? "<empty>" : throwable.getMessage());
        }
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        putBreadcrumbDetails(project, details);
        TelemetryEventEnvelope event = TelemetryEventEnvelope.error(
                project.projectId(),
                project.displayName(),
                "runtime_api",
                sessionId,
                eventName,
                project.pluginIdentifier(),
                project.pluginVersion(),
                normalizedContext.worldName(),
                severityOrDefault(normalizedContext, throwable == null ? TelemetryEventEnvelope.SEVERITY_WARNING : TelemetryEventEnvelope.SEVERITY_ERROR),
                environmentFor(project, runtimeMetadata),
                attributes,
                details,
                normalizedContext,
                runtimeMetadata
        );
        if (!eventStoreFor(project).persist(event)) {
            logWarning("Failed to store telemetry error event for project " + project.projectId() + ".", null);
            return;
        }
        requestFlushAsync("event", project.projectId());
    }

    public void recordLifecycle(@Nonnull String projectId,
                                @Nonnull String eventName,
                                int durationMs,
                                boolean success,
                                @Nullable String detail) {
        recordLifecycle(projectId, eventName, durationMs, success, TelemetryEventContext.builder().detail(detail).build());
    }

    public void recordLifecycle(@Nonnull String projectId,
                                @Nonnull String eventName,
                                int durationMs,
                                boolean success,
                                @Nullable TelemetryEventContext context) {
        if (!enabled.get()) {
            return;
        }
        TelemetryProjectRegistration project = findProject(projectId);
        if (project == null
                || !project.isEnabled()
                || !project.events().lifecycle().enabled()
                || project.resolveEventDeliveryTarget(settings) == null) {
            return;
        }
        TelemetryEventContext normalizedContext = normalizeContext(context);
        CrashReportEnvelope.RuntimeMetadata runtimeMetadata = CrashReportEnvelope.RuntimeMetadata.capture(loadedMods);
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        putDetail(attributes, normalizedContext);
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        if (!success) {
            putBreadcrumbDetails(project, details);
        }
        TelemetryEventEnvelope event = TelemetryEventEnvelope.lifecycle(
                project.projectId(),
                project.displayName(),
                "runtime_api",
                sessionId,
                eventName,
                project.pluginIdentifier(),
                project.pluginVersion(),
                normalizedContext.worldName(),
                success,
                durationMs,
                environmentFor(project, runtimeMetadata),
                attributes,
                details,
                normalizedContext,
                runtimeMetadata
        );
        if (!eventStoreFor(project).persist(event)) {
            logWarning("Failed to store telemetry lifecycle event for project " + project.projectId() + ".", null);
            return;
        }
        requestFlushAsync("event", project.projectId());
    }

    public void recordPerformance(@Nonnull String projectId,
                                  @Nonnull String eventName,
                                  int durationMs,
                                  @Nullable Double metricValue,
                                  @Nullable String detail) {
        recordPerformance(projectId, eventName, durationMs, metricValue, TelemetryEventContext.builder().detail(detail).build());
    }

    public void recordPerformance(@Nonnull String projectId,
                                  @Nonnull String eventName,
                                  int durationMs,
                                  @Nullable Double metricValue,
                                  @Nullable TelemetryEventContext context) {
        if (!enabled.get()) {
            return;
        }
        TelemetryProjectRegistration project = findProject(projectId);
        if (project == null || !project.isEnabled() || project.resolveEventDeliveryTarget(settings) == null) {
            return;
        }
        if (!project.performance().enabled() || durationMs < project.performance().thresholdMs()) {
            return;
        }
        if (project.performance().sampleRate() < 1.0d && ThreadLocalRandom.current().nextDouble() > project.performance().sampleRate()) {
            return;
        }
        TelemetryEventContext normalizedContext = normalizeContext(context);
        CrashReportEnvelope.RuntimeMetadata runtimeMetadata = CrashReportEnvelope.RuntimeMetadata.capture(loadedMods);
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        putDetail(attributes, normalizedContext);
        attributes.put("thresholdMs", project.performance().thresholdMs());
        Map<String, Object> details = project.performance().sanitizeDetails(eventName, normalizedContext.details());
        TelemetryEventEnvelope event = TelemetryEventEnvelope.performance(
                project.projectId(),
                project.displayName(),
                "runtime_api",
                sessionId,
                eventName,
                project.pluginIdentifier(),
                project.pluginVersion(),
                normalizedContext.worldName(),
                durationMs,
                metricValue,
                environmentFor(project, runtimeMetadata),
                attributes,
                details,
                normalizedContext,
                runtimeMetadata
        );
        if (!eventStoreFor(project).persist(event)) {
            logWarning("Failed to store telemetry performance event for project " + project.projectId() + ".", null);
            return;
        }
        requestFlushAsync("event", project.projectId());
    }

    public void recordUsage(@Nonnull String projectId,
                            @Nonnull String eventName,
                            @Nullable String detail) {
        recordUsage(projectId, eventName, TelemetryEventContext.builder().detail(detail).build());
    }

    public void recordUsage(@Nonnull String projectId,
                            @Nonnull String eventName,
                            @Nullable TelemetryEventContext context) {
        if (!enabled.get()) {
            return;
        }
        TelemetryProjectRegistration project = findProject(projectId);
        if (project == null || !project.isEnabled() || project.resolveEventDeliveryTarget(settings) == null) {
            return;
        }
        if (!project.usage().allows(eventName)) {
            return;
        }
        TelemetryEventContext normalizedContext = normalizeContext(context);
        CrashReportEnvelope.RuntimeMetadata runtimeMetadata = CrashReportEnvelope.RuntimeMetadata.capture(loadedMods);
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        putDetail(attributes, normalizedContext);
        Map<String, Object> details = project.usage().sanitizeDetails(eventName, normalizedContext.details());
        TelemetryEventEnvelope event = TelemetryEventEnvelope.usage(
                project.projectId(),
                project.displayName(),
                "runtime_api",
                sessionId,
                eventName,
                project.pluginIdentifier(),
                project.pluginVersion(),
                normalizedContext.worldName(),
                environmentFor(project, runtimeMetadata),
                attributes,
                details,
                normalizedContext,
                runtimeMetadata
        );
        if (!eventStoreFor(project).persist(event)) {
            logWarning("Failed to store telemetry usage event for project " + project.projectId() + ".", null);
            return;
        }
        requestFlushAsync("event", project.projectId());
    }

    public boolean captureTestReport(@Nonnull String projectId, @Nullable String detail) {
        TelemetryProjectRegistration project = findProject(projectId);
        if (project == null || !project.isEnabled()) {
            return false;
        }
        String suffix = detail == null || detail.isBlank() ? "" : ": " + detail.trim();
        recordBreadcrumb(project.projectId(), "telemetry", "Manual test report requested" + suffix + ".");
        captureExplicitProject(
                project.projectId(),
                SOURCE_MANUAL_TEST,
                new RuntimeException("Manual telemetry test for " + project.displayName() + suffix)
        );
        return true;
    }

    public void captureExceptionalWorldRemoval(@Nullable World world,
                                               @Nullable RemoveWorldEvent.RemovalReason removalReason) {
        if (!enabled.get()
                || world == null
                || removalReason != RemoveWorldEvent.RemovalReason.EXCEPTIONAL
                || world.getFailureException() == null) {
            return;
        }
        captureAcrossProjects(
                SOURCE_EXCEPTIONAL_WORLD_REMOVAL,
                world.getFailureException(),
                Thread.currentThread(),
                world.getName(),
                removalReason.name(),
                world.getPossibleFailureCause()
        );
    }

    public int pendingReports(@Nullable String projectId) {
        return totalPendingCount(projectId);
    }

    @Nullable
    public TelemetryProjectRegistration findProject(@Nonnull String projectId) {
        for (TelemetryProjectRegistration project : projects) {
            if (project.projectId().equalsIgnoreCase(projectId)) {
                return project;
            }
        }
        return null;
    }

    @Nonnull
    public FlushSummary flushPendingReportsNow(@Nonnull String reason) {
        return flushPendingReportsNow(reason, null);
    }

    @Nonnull
    public FlushSummary flushPendingReportsNow(@Nonnull String reason, @Nullable String projectIdFilter) {
        if (!enabled.get()) {
            FlushSummary summary = new FlushSummary(0, 0, totalPendingCount(projectIdFilter), "disabled");
            updateFlushStatus(reason, summary, null);
            return summary;
        }

        int attempted = 0;
        int uploaded = 0;
        String lastFailure = null;
        try {
            for (TelemetryProjectRegistration project : matchingProjects(projectIdFilter)) {
                if (!project.isEnabled()) {
                    continue;
                }
                CrashReportClient.DeliveryTarget target = project.resolveDeliveryTarget(settings);
                if (target != null && !target.endpoint().isBlank()) {
                    CrashReportStore store = storeFor(project);
                    for (CrashReportStore.PendingReport pending : store.listPendingReports(settings.maxUploadsPerFlush())) {
                        attempted++;
                        CrashReportClient.UploadResult uploadResult = client.upload(target, pending.payload());
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
                }

                CrashReportClient.DeliveryTarget eventTarget = project.resolveEventDeliveryTarget(settings);
                if (eventTarget == null || eventTarget.endpoint().isBlank()) {
                    continue;
                }
                TelemetryEventStore eventStore = eventStoreFor(project);
                for (TelemetryEventStore.PendingEvent pending : eventStore.listPendingEvents(settings.maxUploadsPerFlush())) {
                    attempted++;
                    CrashReportClient.UploadResult uploadResult = client.upload(eventTarget, pending.payload());
                    if (uploadResult.success()) {
                        if (eventStore.delete(pending.path())) {
                            uploaded++;
                        } else {
                            lastFailure = "Uploaded but failed to remove local event file " + pending.path().getFileName();
                        }
                    } else {
                        lastFailure = uploadResult.detail() == null
                                ? "HTTP status " + uploadResult.statusCode()
                                : uploadResult.detail();
                    }
                }
            }
            FlushSummary summary = new FlushSummary(attempted, uploaded, totalPendingCount(projectIdFilter), lastFailure);
            updateFlushStatus(reason, summary, lastFailure);
            return summary;
        } catch (Exception ex) {
            FlushSummary summary = new FlushSummary(attempted, uploaded, totalPendingCount(projectIdFilter), ex.getMessage());
            updateFlushStatus(reason, summary, ex.getMessage());
            logWarning("Crash telemetry flush pass failed.", ex);
            return summary;
        }
    }

    private void captureExplicitProject(@Nonnull String projectId,
                                        @Nonnull String source,
                                        @Nullable Throwable throwable) {
        if (!enabled.get() || throwable == null) {
            return;
        }
        TelemetryProjectRegistration project = findProject(projectId);
        if (project == null || !project.isEnabled() || !project.capturesSource(source)) {
            return;
        }

        try {
            CrashAttribution.AttributionResult attribution = CrashAttribution.explicit(
                    throwable,
                    project.pluginIdentifier(),
                    project.packagePrefixes()
            );
            persist(project, source, attribution, throwable, Thread.currentThread().getName(), null, null, null);
            requestFlushAsync("capture", project.projectId());
        } catch (Throwable captureFailure) {
            logWarning("Explicit crash telemetry capture failed for project " + projectId + ".", captureFailure);
        }
    }

    private void captureAcrossProjects(@Nonnull String source,
                                       @Nullable Throwable throwable,
                                       @Nullable Thread thread,
                                       @Nullable String worldName,
                                       @Nullable String worldRemovalReason,
                                       @Nullable PluginIdentifier worldFailurePluginIdentifier) {
        if (!enabled.get() || throwable == null) {
            return;
        }

        boolean capturedAny = false;
        String identifiedPluginHint = worldFailurePluginIdentifier == null ? null : worldFailurePluginIdentifier.toString();
        String threadName = thread == null ? Thread.currentThread().getName() : thread.getName();
        for (TelemetryProjectRegistration project : projects) {
            if (!project.isEnabled() || !project.capturesSource(source)) {
                continue;
            }
            try {
                CrashAttribution.AttributionResult attribution = CrashAttribution.classify(
                        throwable,
                        project.ownerPluginIdentifiers(),
                        project.packagePrefixes(),
                        identifiedPluginHint
                );
                if (!attribution.attributed()) {
                    continue;
                }
                persist(
                        project,
                        source,
                        attribution,
                        throwable,
                        threadName,
                        worldName,
                        worldRemovalReason,
                        identifiedPluginHint
                );
                capturedAny = true;
            } catch (Throwable captureFailure) {
                logWarning("Crash telemetry capture failed for project " + project.projectId() + ".", captureFailure);
            }
        }

        if (capturedAny) {
            requestFlushAsync("capture", null);
        }
    }

    private void persist(@Nonnull TelemetryProjectRegistration project,
                         @Nonnull String source,
                         @Nonnull CrashAttribution.AttributionResult attribution,
                         @Nonnull Throwable throwable,
                         @Nonnull String threadName,
                         @Nullable String worldName,
                         @Nullable String worldRemovalReason,
                         @Nullable String worldFailurePluginIdentifier) {
        CrashReportEnvelope.RuntimeMetadata runtimeMetadata = CrashReportEnvelope.RuntimeMetadata.capture(loadedMods);
        CrashReportEnvelope envelope = CrashReportEnvelope.create(
                project.projectId(),
                project.displayName(),
                source,
                sessionId,
                attribution.fingerprint(),
                project.pluginIdentifier(),
                project.pluginVersion(),
                threadName,
                worldName,
                worldRemovalReason,
                worldFailurePluginIdentifier,
                environmentFor(project, runtimeMetadata),
                attribution,
                breadcrumbs.snapshot(project.projectId()),
                throwable,
                runtimeMetadata
        );
        CrashReportStore.WriteResult result = storeFor(project).persist(envelope);
        if (result == CrashReportStore.WriteResult.FAILED) {
            logWarning("Failed to store crash telemetry report for project " + project.projectId() + ".", null);
        }
    }

    @Nonnull
    private static TelemetryEventContext normalizeContext(@Nullable TelemetryEventContext context) {
        return context == null ? TelemetryEventContext.empty() : context.normalize();
    }

    private static void putDetail(@Nonnull Map<String, Object> attributes,
                                  @Nonnull TelemetryEventContext context) {
        if (context.detail() != null && !context.detail().isBlank()) {
            attributes.put("detail", context.detail().trim());
        }
    }

    private void putBreadcrumbDetails(@Nonnull TelemetryProjectRegistration project,
                                      @Nonnull Map<String, Object> details) {
        if (!project.events().breadcrumbs().enabled()) {
            return;
        }
        List<CrashReportEnvelope.BreadcrumbEntry> snapshot = breadcrumbs.snapshot(project.projectId());
        if (!snapshot.isEmpty()) {
            details.put("breadcrumbs", snapshot);
        }
    }

    @Nonnull
    private static String severityOrDefault(@Nonnull TelemetryEventContext context,
                                            @Nonnull String fallback) {
        return context.severity() == null || context.severity().isBlank() ? fallback : context.severity();
    }

    @Nonnull
    private CrashReportEnvelope.EnvironmentSnapshot environmentFor(@Nonnull TelemetryProjectRegistration project,
                                                                   @Nonnull CrashReportEnvelope.RuntimeMetadata runtimeMetadata) {
        String key = project.projectId().toLowerCase(Locale.ROOT);
        CrashReportEnvelope.EnvironmentSnapshot existing = environmentsByProjectId.get(key);
        if (existing != null) {
            return existing;
        }
        CrashReportEnvelope.EnvironmentSnapshot snapshot = CrashReportEnvelope.EnvironmentSnapshot.capture(
                project.projectId(),
                project.pluginIdentifier(),
                project.pluginVersion(),
                project.runtimeMode(),
                runtimeMetadata
        );
        environmentsByProjectId.put(key, snapshot);
        return snapshot;
    }

    private boolean requestFlushAsync(@Nonnull String reason, @Nullable String projectIdFilter) {
        if (!enabled.get() || executor == null || matchingProjects(projectIdFilter).isEmpty()) {
            return false;
        }
        if (!flushInProgress.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    flushPendingReportsNow(reason, projectIdFilter);
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

    @Nonnull
    private List<TelemetryProjectRegistration> matchingProjects(@Nullable String projectIdFilter) {
        if (projectIdFilter == null || projectIdFilter.isBlank()) {
            return projects;
        }
        TelemetryProjectRegistration project = findProject(projectIdFilter);
        return project == null ? List.of() : List.of(project);
    }

    private CrashReportStore storeFor(@Nonnull TelemetryProjectRegistration project) {
        return storesByProjectId.computeIfAbsent(
                project.projectId().toLowerCase(Locale.ROOT),
                ignored -> new CrashReportStore(
                        dataPaths.pendingDirectory(project.projectId()),
                        settings.maxPendingReportsPerProject(),
                        logger
                )
        );
    }

    private TelemetryEventStore eventStoreFor(@Nonnull TelemetryProjectRegistration project) {
        return eventStoresByProjectId.computeIfAbsent(
                project.projectId().toLowerCase(Locale.ROOT),
                ignored -> new TelemetryEventStore(
                        dataPaths.pendingEventsDirectory(project.projectId()),
                        settings.maxPendingEventsPerProject(),
                        logger
                )
        );
    }

    private int totalPendingCount(@Nullable String projectIdFilter) {
        int total = 0;
        for (TelemetryProjectRegistration project : matchingProjects(projectIdFilter)) {
            total += storeFor(project).pendingCount();
            total += eventStoreFor(project).pendingCount();
        }
        return total;
    }

    private void installUncaughtExceptionHandler() {
        if (!uncaughtHandlerInstalled.compareAndSet(false, true)) {
            return;
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        TelemetryUncaughtExceptionHandler handler = new TelemetryUncaughtExceptionHandler(previous);
        previousUncaughtHandler = previous;
        installedUncaughtHandler = handler;
        Thread.setDefaultUncaughtExceptionHandler(handler);
    }

    private void uninstallUncaughtExceptionHandler() {
        if (!uncaughtHandlerInstalled.compareAndSet(true, false)) {
            return;
        }
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        TelemetryUncaughtExceptionHandler installed = installedUncaughtHandler;
        if (installed != null && current == installed) {
            Thread.setDefaultUncaughtExceptionHandler(skipStoppedTelemetryHandlers(previousUncaughtHandler));
        }
        installedUncaughtHandler = null;
        previousUncaughtHandler = null;
    }

    @Nullable
    private Thread.UncaughtExceptionHandler skipStoppedTelemetryHandlers(
            @Nullable Thread.UncaughtExceptionHandler candidate) {
        Thread.UncaughtExceptionHandler current = candidate;
        while (current instanceof TelemetryUncaughtExceptionHandler telemetryHandler
                && !telemetryHandler.owner.isActiveInstalledHandler(telemetryHandler)) {
            current = telemetryHandler.previous;
        }
        return current;
    }

    private boolean isActiveInstalledHandler(@Nonnull TelemetryUncaughtExceptionHandler handler) {
        return uncaughtHandlerInstalled.get() && installedUncaughtHandler == handler;
    }

    private void updateFlushStatus(@Nonnull String reason,
                                   @Nonnull FlushSummary summary,
                                   @Nullable String detail) {
        lastFlushResult = "reason=" + reason
                + ", attempted=" + summary.attempted()
                + ", uploaded=" + summary.uploaded()
                + ", pending=" + summary.pendingAfter()
                + (detail == null || detail.isBlank() ? "" : ", detail=" + detail);
        if (logger != null) {
            logger.at(Level.FINE).log("Crash telemetry flush: " + lastFlushResult);
        }
    }

    private void logWarning(@Nonnull String message, @Nullable Throwable throwable) {
        if (logger == null) {
            return;
        }
        if (throwable == null) {
            logger.at(Level.WARNING).log(message + " Last flush status: " + lastFlushResult);
            return;
        }
        logger.at(Level.WARNING).withCause(throwable).log(message);
    }

    /**
     * One flush pass summary.
     */
    public record FlushSummary(int attempted, int uploaded, int pendingAfter, @Nullable String lastFailure) {
    }

    /**
     * Chained uncaught handler that becomes a no-op once its owning engine is shut down.
     */
    private final class TelemetryUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

        private final Thread.UncaughtExceptionHandler previous;
        private final TelemetryCoreEngine owner;

        private TelemetryUncaughtExceptionHandler(@Nullable Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
            this.owner = TelemetryCoreEngine.this;
        }

        @Override
        public void uncaughtException(@Nullable Thread thread, @Nonnull Throwable throwable) {
            if (owner.isActiveInstalledHandler(this)) {
                try {
                    owner.captureAcrossProjects(SOURCE_UNCAUGHT_EXCEPTION, throwable, thread, null, null, null);
                } catch (Exception captureFailure) {
                    owner.logWarning("Crash telemetry uncaught handler capture failed.", captureFailure);
                }
            }

            if (previous != null) {
                try {
                    previous.uncaughtException(thread, throwable);
                } catch (Exception delegateFailure) {
                    owner.logWarning("Delegated uncaught exception handler failed.", delegateFailure);
                }
            }
        }
    }
}
