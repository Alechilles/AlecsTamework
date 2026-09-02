package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.beacon.api.TelemetryDiagnosticAttachment;
import com.alechilles.beacon.api.TelemetryDiagnosticBundle;
import com.alechilles.beacon.api.TelemetryDiagnosticBundleResult;
import com.alechilles.beacon.api.TelemetryDiagnosticDisposition;
import com.alechilles.alecstamework.persistence.runtime.PersistenceFailureSignal;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Builds and submits bounded persistence diagnostics outside storage threads.
 */
public final class PersistenceAutomaticDiagnosticReporter
        implements Consumer<PersistenceFailureSignal>, AutoCloseable {

    public static final int MAX_PACKAGE_BYTES = 524_288;
    private static final int MAX_PENDING_REPORTS = 16;
    private static final int MAX_RECENT_INCIDENTS = 256;
    private static final long WARNING_INTERVAL_MS = 60_000L;

    private final Function<PersistenceFailureContext,
            PersistenceDiagnosticExporter.FailurePackage> packageFactory;
    private final Function<TelemetryDiagnosticBundle,
            TelemetryDiagnosticBundleResult> submitter;
    private final Executor executor;
    @Nullable private final ExecutorService ownedExecutor;
    @Nullable private final HytaleLogger logger;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong lastWarningAt = new AtomicLong(0L);
    private final ThreadLocal<Boolean> reporting =
            ThreadLocal.withInitial(() -> false);
    private final LinkedHashMap<String, Boolean> recentIncidents =
            new LinkedHashMap<>();

    public PersistenceAutomaticDiagnosticReporter(
            @Nonnull Supplier<PersistenceDiagnosticExporter> exporter,
            @Nonnull Function<TelemetryDiagnosticBundle,
                    TelemetryDiagnosticBundleResult> submitter,
            @Nullable HytaleLogger logger
    ) {
        this(
                context -> Objects.requireNonNull(
                        exporter.get(), "diagnostic exporter"
                ).exportFailurePackage(context, MAX_PACKAGE_BYTES),
                submitter,
                createExecutor(),
                logger,
                true
        );
    }

    PersistenceAutomaticDiagnosticReporter(
            @Nonnull Function<PersistenceFailureContext,
                    PersistenceDiagnosticExporter.FailurePackage> packageFactory,
            @Nonnull Function<TelemetryDiagnosticBundle,
                    TelemetryDiagnosticBundleResult> submitter,
            @Nonnull Executor executor,
            @Nullable HytaleLogger logger
    ) {
        this(packageFactory, submitter, executor, logger, false);
    }

    PersistenceAutomaticDiagnosticReporter(
            @Nonnull Function<PersistenceFailureContext,
                    PersistenceDiagnosticExporter.FailurePackage> packageFactory,
            @Nonnull Function<TelemetryDiagnosticBundle,
                    TelemetryDiagnosticBundleResult> submitter,
            @Nonnull ExecutorService executor,
            @Nullable HytaleLogger logger,
            boolean ownsExecutor
    ) {
        this(packageFactory, submitter, (Executor) executor, logger, ownsExecutor);
    }

    private PersistenceAutomaticDiagnosticReporter(
            @Nonnull Function<PersistenceFailureContext,
                    PersistenceDiagnosticExporter.FailurePackage> packageFactory,
            @Nonnull Function<TelemetryDiagnosticBundle,
                    TelemetryDiagnosticBundleResult> submitter,
            @Nonnull Executor executor,
            @Nullable HytaleLogger logger,
            boolean ownsExecutor
    ) {
        this.packageFactory = Objects.requireNonNull(
                packageFactory, "packageFactory"
        );
        this.submitter = Objects.requireNonNull(submitter, "submitter");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownsExecutor
                ? (ExecutorService) executor
                : null;
        this.logger = logger;
    }

    /** Accepts one terminal failure without blocking its persistence caller. */
    @Override
    public void accept(@Nonnull PersistenceFailureSignal signal) {
        Objects.requireNonNull(signal, "signal");
        String reportKey = fingerprint(signal);
        if (closed.get() || reporting.get() || !reserve(reportKey)) {
            return;
        }
        try {
            executor.execute(() -> submit(signal, reportKey));
        } catch (RejectedExecutionException rejected) {
            release(reportKey);
            warn("Automatic persistence diagnostic queue is full.", rejected);
        } catch (RuntimeException failure) {
            release(reportKey);
            warn("Could not schedule an automatic persistence diagnostic.", failure);
        }
    }

    private void submit(
            @Nonnull PersistenceFailureSignal signal,
            @Nonnull String reportKey
    ) {
        if (reporting.get()) {
            release(reportKey);
            return;
        }
        reporting.set(true);
        try {
            PersistenceFailureContext context = new PersistenceFailureContext(
                    signal.eventName(), signal.incidentKey(), signal.operation(),
                    signal.phase(), signal.reason(), signal.cause()
            );
            PersistenceDiagnosticExporter.FailurePackage failurePackage =
                    packageFactory.apply(context);
            String diagnosticId = UUID.randomUUID().toString();
            TelemetryDiagnosticAttachment attachment =
                    TelemetryDiagnosticAttachment.binary(
                            diagnosticId + "-debugdb",
                            "tamework_debugdb_export",
                            "tamework-debugdb-" + failurePackage.supportId() + ".zip",
                            "application/zip",
                            failurePackage.content()
                    );
            TelemetryDiagnosticBundleResult result = submitter.apply(
                    new TelemetryDiagnosticBundle(
                            diagnosticId,
                            Instant.now().toString(),
                            "automatic",
                            "persistence_failure",
                            "Tamework persistence failure",
                            "Tamework detected a terminal persistence failure. "
                                    + "A redacted debug database export is attached.",
                            "error",
                            TelemetryDiagnosticDisposition.createOrJoinIssue(
                                    fingerprint(signal)
                            ),
                            attributes(signal),
                            List.of(attachment)
                    )
            );
            handleResult(result, reportKey, diagnosticId);
        } catch (RuntimeException failure) {
            release(reportKey);
            warn("Could not build or submit an automatic persistence diagnostic.", failure);
        } finally {
            reporting.remove();
        }
    }

    private void handleResult(
            @Nullable TelemetryDiagnosticBundleResult result,
            @Nonnull String reportKey,
            @Nonnull String diagnosticId
    ) {
        if (result == null || !result.accepted()) {
            release(reportKey);
            if (logger != null) {
                String detail = result == null
                        ? "no result"
                        : result.status() + ": " + result.detail();
                logger.at(Level.WARNING).log(
                        "Automatic persistence diagnostic was not accepted ("
                                + detail + ")."
                );
            }
            return;
        }
        if (logger != null) {
            logger.at(Level.INFO).log(
                    "Automatic persistence diagnostic queued: " + diagnosticId
            );
        }
    }

    @Nonnull
    private static Map<String, Object> attributes(
            @Nonnull PersistenceFailureSignal signal
    ) {
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("eventName", safeToken(signal.eventName()));
        attributes.put("operation", safeToken(signal.operation()));
        attributes.put("phase", safeToken(signal.phase()));
        attributes.put("reason", safeToken(signal.reason()));
        Throwable cause = signal.cause();
        if (cause != null) {
            attributes.put("exceptionClass", cause.getClass().getName());
        }
        return Map.copyOf(attributes);
    }

    @Nonnull
    private static String fingerprint(@Nonnull PersistenceFailureSignal signal) {
        Throwable cause = signal.cause();
        String classification = safeToken(signal.eventName()) + '|'
                + safeToken(signal.operation()) + '|'
                + safeToken(signal.phase()) + '|'
                + safeToken(signal.reason()) + '|'
                + (cause == null ? "none" : cause.getClass().getName());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    classification.getBytes(StandardCharsets.UTF_8)
            );
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    @Nonnull
    private static String safeToken(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 80));
    }

    private synchronized boolean reserve(@Nonnull String incidentKey) {
        if (recentIncidents.containsKey(incidentKey)) {
            return false;
        }
        recentIncidents.put(incidentKey, Boolean.TRUE);
        if (recentIncidents.size() > MAX_RECENT_INCIDENTS) {
            Iterator<String> iterator = recentIncidents.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return true;
    }

    private synchronized void release(@Nonnull String incidentKey) {
        recentIncidents.remove(incidentKey);
    }

    private void warn(@Nonnull String message, @Nonnull Throwable failure) {
        if (logger == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = lastWarningAt.get();
        if (now - previous < WARNING_INTERVAL_MS
                || !lastWarningAt.compareAndSet(previous, now)) {
            return;
        }
        logger.at(Level.WARNING).withCause(failure).log(message);
    }

    /** Stops new work and gives queued submissions a short drain window. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || ownedExecutor == null) {
            return;
        }
        ownedExecutor.shutdown();
        try {
            if (!ownedExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                ownedExecutor.shutdownNow();
                ownedExecutor.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ownedExecutor.shutdownNow();
        }
    }

    @Nonnull
    private static ExecutorService createExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_REPORTS),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "AlecTamework-PersistenceDiagnostics"
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
