package com.alechilles.alecstamework;

import com.alechilles.alecstamework.metrics.BondedCompanionPersistenceTelemetry;
import com.alechilles.alecstamework.metrics.CrashTelemetryService;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionStorageFailureEvidence;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics
        .PersistenceAutomaticDiagnosticReporter;
import com.alechilles.alecstamework.persistence.diagnostics
        .PersistenceDiagnosticExporter;
import com.alechilles.alecstamework.persistence.diagnostics
        .PersistenceFailureRelay;
import com.alechilles.alecstamework.persistence.runtime.PersistenceFailureSignal;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Owns Tamework telemetry and its automatic persistence diagnostic bridge.
 */
final class TameworkDiagnosticRuntime implements AutoCloseable {

    private final CrashTelemetryService telemetry;
    private final HytaleLogger logger;
    private final PersistenceFailureRelay failureRelay =
            new PersistenceFailureRelay();
    private final AtomicReference<PersistenceDiagnosticExporter> exporter =
            new AtomicReference<>();
    @Nullable private PersistenceAutomaticDiagnosticReporter reporter;
    @Nullable private Path persistenceDataDirectory;
    private boolean reporterBound;

    private TameworkDiagnosticRuntime(@Nonnull CrashTelemetryService telemetry,
                                      @Nonnull HytaleLogger logger) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Creates telemetry or leaves the plugin operational when it is unavailable. */
    @Nullable
    static TameworkDiagnosticRuntime create(@Nonnull Tamework plugin) {
        try {
            return new TameworkDiagnosticRuntime(
                    CrashTelemetryService.create(plugin), plugin.getLogger()
            );
        } catch (Exception failure) {
            plugin.getLogger().at(Level.WARNING).withCause(failure).log(
                    "Failed to initialize Tamework embedded telemetry; "
                            + "continuing without telemetry."
            );
            return null;
        }
    }

    /** Creates the bounded asynchronous reporter before persistence opens. */
    void preparePersistence(@Nonnull Path dataDirectory) {
        persistenceDataDirectory = Objects.requireNonNull(
                dataDirectory, "dataDirectory"
        );
        reporter = new PersistenceAutomaticDiagnosticReporter(
                exporter::get,
                telemetry::submitDiagnosticBundle,
                logger
        );
    }

    @Nonnull
    Consumer<PersistenceFailureSignal> failureSink() {
        return failureRelay;
    }

    void useExporter(@Nonnull PersistenceDiagnosticExporter exporter) {
        this.exporter.set(Objects.requireNonNull(exporter, "exporter"));
        bindReporterIfReady();
    }

    @Nonnull
    PersistenceDiagnosticExporter useBondedExporter(
            @Nonnull BondedCompanionDiagnosticContributor contributor
    ) {
        Path dataDirectory = Objects.requireNonNull(
                persistenceDataDirectory,
                "Persistence diagnostics are not prepared"
        );
        PersistenceDiagnosticExporter bondedExporter =
                PersistenceDiagnosticExporter.bondedOnly(
                        dataDirectory, contributor
                );
        useExporter(bondedExporter);
        return bondedExporter;
    }

    void onBondedFailure(
            @Nonnull BondedCompanionStorageFailureEvidence evidence
    ) {
        BondedCompanionPersistenceTelemetry.recordRuntimeFailure(evidence);
        failureRelay.accept(new PersistenceFailureSignal(
                BondedCompanionPersistenceTelemetry.RUNTIME_FAILURE_EVENT,
                "bonded:" + evidence.operation() + ":" + evidence.failureReason(),
                evidence.operation(),
                "bonded_runtime",
                evidence.failureReason(),
                evidence.failure()
        ));
    }

    void start() {
        telemetry.start();
    }

    void recordStartCompleted() {
        telemetry.recordBreadcrumb("lifecycle", "Tamework start completed.");
    }

    void captureStartFailure(@Nullable Throwable failure) {
        if (failure != null) {
            telemetry.captureStartFailure(failure);
        }
    }

    void captureExceptionalWorldRemoval(@Nullable RemoveWorldEvent event) {
        if (event != null && event.getRemovalReason()
                == RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            telemetry.captureExceptionalWorldRemoval(
                    event.getWorld(), event.getRemovalReason()
            );
        }
    }

    @Nonnull
    CrashTelemetryService telemetry() {
        return telemetry;
    }

    private void bindReporterIfReady() {
        if (!reporterBound && reporter != null && exporter.get() != null) {
            failureRelay.bind(reporter);
            reporterBound = true;
        }
    }

    /** Drains diagnostic work before stopping embedded telemetry. */
    @Override
    public void close() {
        if (reporter != null) {
            failureRelay.unbind(reporter);
            reporterBound = false;
            reporter.close();
            reporter = null;
        }
        exporter.set(null);
        persistenceDataDirectory = null;
        telemetry.shutdown();
    }
}
