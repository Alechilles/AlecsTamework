package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteKernelShutdownReport;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceKernel;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePublicCanonicalSnapshot;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePublicControlSnapshot;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePublicPersistenceAdapter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePublicProjectionStartupResult;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePublicRecoveryResult;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLease;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupAction;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupCoordinator;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceSchemaStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTarget;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTargetOpener;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Mutable composition state owned exclusively by one public runtime. */
final class PublicPersistenceRuntimeState {
    private final PublicPersistenceRuntimeConfiguration configuration;
    private final PersistenceFeatureRegistry registry;
    private final PublicPersistenceTargetOpener targets;
    private final PublicPersistenceWorkflowTracker workflows;
    private final PublicPersistenceControlPlane control;
    private PublicPersistenceDiagnosticsAssembler diagnostics;
    private PersistenceStartupCoordinator startup;
    private PersistenceEngineLease lease;
    private PublicPersistenceTarget target;
    private SqliteSchemaV1Manager schemas;
    private SqlitePersistenceKernel kernel;
    private SqlitePublicPersistenceAdapter adapter;
    private PublicPersistenceOperations operations;
    private PublicPersistenceQueries queries;
    private SqlitePublicCanonicalSnapshot canonical;
    private boolean worldQuiesced;
    private PublicPersistenceShutdownReport terminalShutdown;

    PublicPersistenceRuntimeState(
            PublicPersistenceRuntimeConfiguration configuration,
            PersistenceFeatureRegistry registry,
            PublicPersistenceWorkflowTracker workflows
    ) {
        this.configuration = configuration;
        this.registry = registry;
        this.workflows = workflows;
        control = new PublicPersistenceControlPlane(registry);
        targets = new PublicPersistenceTargetOpener(configuration.clock());
    }

    void bind(PersistenceStartupCoordinator startup) {
        if (this.startup != null || startup == null) {
            throw new IllegalStateException(
                    "public_persistence_startup_already_bound"
            );
        }
        this.startup = startup;
        control.bind(startup);
        diagnostics = new PublicPersistenceDiagnosticsAssembler(
                registry, startup, control
        );
    }

    Map<PersistenceStartupNode, PersistenceStartupAction> actions() {
        EnumMap<PersistenceStartupNode, PersistenceStartupAction> actions =
                new EnumMap<>(PersistenceStartupNode.class);
        actions.put(PersistenceStartupNode.OPEN_TARGET, this::openTarget);
        actions.put(PersistenceStartupNode.VALIDATE_SCHEMA, this::validateSchema);
        actions.put(PersistenceStartupNode.LOAD_CANONICAL, this::loadCanonical);
        actions.put(PersistenceStartupNode.RECOVER_OPERATIONS, this::recoverOperations);
        actions.put(PersistenceStartupNode.BUILD_PROJECTIONS, this::buildProjections);
        actions.put(PersistenceStartupNode.LOAD_FEATURE_DETAIL, this::loadFeatureDetail);
        actions.put(PersistenceStartupNode.WAIT_WORLD_EVIDENCE, this::awaitWorldEvidence);
        actions.put(PersistenceStartupNode.RECONCILE_WORLD, this::reconcileWorld);
        actions.put(PersistenceStartupNode.PUBLISH_READ_READINESS, this::publishReadReadiness);
        actions.put(PersistenceStartupNode.PUBLISH_MUTATION_READINESS,
                this::publishMutationReadiness);
        return Map.copyOf(actions);
    }

    Optional<Path> databasePath() {
        return target == null
                ? Optional.empty()
                : Optional.of(target.databasePath());
    }

    Optional<PublicPersistenceTarget.Origin> targetOrigin() {
        return target == null
                ? Optional.empty()
                : Optional.of(target.origin());
    }

    SqlitePublicPersistenceAdapter requireAdapter() {
        if (adapter == null) {
            throw new IllegalStateException(
                    "public_persistence_adapter_not_open"
            );
        }
        return adapter;
    }

    PublicPersistenceOperations requireOperations() {
        requireAdapter();
        return operations;
    }

    PublicPersistenceQueries requireQueries() {
        requireAdapter();
        return queries;
    }

    PublicPersistenceWorkflowTracker workflows() {
        return workflows;
    }

    PublicPersistenceMetricsSnapshot metrics() {
        return control.snapshot();
    }

    CompletionStage<PersistenceReadResult<
            PublicPersistenceDiagnosticsSnapshot>> diagnostics() {
        return requireAdapter().diagnostics().thenApply(
                diagnostics::assemble
        );
    }

    synchronized PublicPersistenceShutdownReport shutdown(Duration timeout) {
        if (terminalShutdown != null) {
            return terminalShutdown;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        startup.close();
        try {
            if (!worldQuiesced) {
                configuration.worldReconciliation().quiesce();
                worldQuiesced = true;
            }
        } catch (Throwable failure) {
            return report(
                    PublicPersistenceShutdownReport.Status.QUIESCE_FAILED,
                    null,
                    failure
            );
        }
        PublicPersistenceWorkflowTracker.DrainResult drained =
                workflows.drain(remaining(deadline));
        if (!drained.drained()) {
            return new PublicPersistenceShutdownReport(
                    PublicPersistenceShutdownReport.Status
                            .FEATURE_DRAIN_TIMED_OUT,
                    drained.outstanding(),
                    null,
                    null
            );
        }
        SqliteKernelShutdownReport kernelReport =
                kernel == null ? null : kernel.shutdown(remaining(deadline));
        if (kernelReport != null
                && kernel.state() != SqlitePersistenceKernel.State.CLOSED) {
            return report(
                    PublicPersistenceShutdownReport.Status
                            .KERNEL_DRAIN_TIMED_OUT,
                    kernelReport,
                    null
            );
        }
        try {
            if (lease != null) {
                if (kernelReport == null || kernelReport.clean()) {
                    lease.close();
                } else {
                    lease.closeUnclean();
                }
            }
        } catch (Throwable failure) {
            return report(
                    PublicPersistenceShutdownReport.Status
                            .CONTROL_CLOSE_FAILED,
                    kernelReport,
                    failure
            );
        }
        PublicPersistenceShutdownReport.Status status =
                kernelReport == null || kernelReport.clean()
                        ? PublicPersistenceShutdownReport.Status.COMPLETE
                        : PublicPersistenceShutdownReport.Status.COMPLETE_UNCLEAN;
        terminalShutdown = report(status, kernelReport, null);
        return terminalShutdown;
    }

    private CompletionStage<PersistenceStartupAction.Result> openTarget() {
        lease = PersistenceEngineLease.acquireReplacement(
                configuration.dataDirectory()
        );
        target = targets.open(configuration.dataDirectory());
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(target.databasePath());
        schemas = new SqliteSchemaV1Manager(
                connections,
                configuration.clock()
        );
        kernel = new SqlitePersistenceKernel(connections, control);
        adapter = new SqlitePublicPersistenceAdapter(
                registry,
                kernel,
                control,
                configuration.clock(),
                configuration.refunds(),
                configuration.profileListener()
        );
        operations = new PublicPersistenceOperations(
                adapter,
                configuration.liveBoundaries(),
                workflows
        );
        queries = new PublicPersistenceQueries(adapter);
        return complete();
    }

    private CompletionStage<PersistenceStartupAction.Result> validateSchema() {
        PersistenceReadResult<PersistenceSchemaStatus> result =
                schemas.verify();
        if (!(result instanceof
                PersistenceReadResult.Found<PersistenceSchemaStatus> found)
                || found.value().version() != SqliteSchemaV1Manager.VERSION
                || !found.value().integrityVerified()) {
            throw new IllegalStateException(
                    "replacement_schema_validation_failed"
            );
        }
        return complete();
    }

    private CompletionStage<PersistenceStartupAction.Result> loadCanonical() {
        return workflows.track(adapter.loadCanonical().thenApply(result -> {
            if (!(result instanceof PersistenceReadResult.Found<
                    SqlitePublicCanonicalSnapshot> found)) {
                throw new IllegalStateException(
                        "canonical_startup_read_failed"
                );
            }
            canonical = found.value();
            for (ScopeQuarantine quarantine
                    : canonical.activeQuarantines()) {
                startup.quarantine(
                        quarantine.scope(),
                        quarantine.reasonCode()
                );
            }
            return PersistenceStartupAction.Result.COMPLETE;
        }));
    }

    private CompletionStage<PersistenceStartupAction.Result>
    recoverOperations() {
        return workflows.track(adapter.recover(
                configuration.liveBoundaries(),
                configuration.workerId()
        ).thenApply(result -> {
            for (var scope : result.quarantinedScopes()) {
                startup.quarantine(scope, "startup_operation_recovery");
            }
            if (result.status()
                    != SqlitePublicRecoveryResult.Status.COMPLETE) {
                throw new IllegalStateException(
                        "operation_recovery_failed:"
                                + result.status().name().toLowerCase(
                                java.util.Locale.ROOT
                        ),
                        result.failure()
                );
            }
            return PersistenceStartupAction.Result.COMPLETE;
        }));
    }

    private CompletionStage<PersistenceStartupAction.Result>
    buildProjections() {
        return workflows.track(adapter.buildProjections().thenApply(result -> {
            if (result.status()
                    != SqlitePublicProjectionStartupResult.Status.COMPLETE) {
                throw new IllegalStateException(
                        "projection_startup_failed:"
                                + result.status().name().toLowerCase(
                                java.util.Locale.ROOT
                        ),
                        result.failure()
                );
            }
            return PersistenceStartupAction.Result.COMPLETE;
        }));
    }

    private CompletionStage<PersistenceStartupAction.Result>
    loadFeatureDetail() {
        if (canonical == null) {
            throw new IllegalStateException(
                    "canonical_feature_detail_unavailable"
            );
        }
        return workflows.track(
                adapter.synchronizeControlPlane().thenApply(result -> {
                    if (result instanceof PersistenceTransactionResult
                            .Committed<?> committed
                            && committed.value()
                            instanceof SqlitePublicControlSnapshot control) {
                        startup.installFeatureCircuits(
                                control.circuits()
                        );
                        return PersistenceStartupAction.Result.COMPLETE;
                    }
                    throw controlFailure(result);
                })
        );
    }

    private CompletionStage<PersistenceStartupAction.Result>
    awaitWorldEvidence() {
        return mapWorld(configuration.worldReconciliation().awaitEvidence());
    }

    private CompletionStage<PersistenceStartupAction.Result> reconcileWorld() {
        return mapWorld(configuration.worldReconciliation().reconcile());
    }

    private CompletionStage<PersistenceStartupAction.Result>
    publishReadReadiness() {
        return complete();
    }

    private CompletionStage<PersistenceStartupAction.Result>
    publishMutationReadiness() {
        lease.publishStartupComplete();
        return complete();
    }

    private CompletionStage<PersistenceStartupAction.Result> mapWorld(
            CompletionStage<PublicPersistenceWorldReconciliation.Result> stage
    ) {
        if (stage == null) {
            throw new IllegalStateException(
                    "world_reconciliation_returned_null"
            );
        }
        return workflows.track(stage.thenApply(result -> switch (result) {
            case COMPLETE -> PersistenceStartupAction.Result.COMPLETE;
            case DEFERRED -> PersistenceStartupAction.Result.DEFERRED;
        }));
    }

    private CompletionStage<PersistenceStartupAction.Result> complete() {
        return CompletableFuture.completedFuture(
                PersistenceStartupAction.Result.COMPLETE
        );
    }

    private IllegalStateException controlFailure(
            PersistenceTransactionResult<?> result
    ) {
        if (result instanceof PersistenceTransactionResult.RolledBack<?> failed) {
            return new IllegalStateException(
                    "feature_control_synchronization_failed:"
                            + failed.failure().code(),
                    failed.failure().cause()
            );
        }
        PersistenceTransactionResult.Unknown<?> unknown =
                (PersistenceTransactionResult.Unknown<?>) result;
        return new IllegalStateException(
                "feature_control_synchronization_unknown:"
                        + unknown.failure().code(),
                unknown.failure().cause()
        );
    }

    private Duration remaining(long deadlineNs) {
        return Duration.ofNanos(
                Math.max(0, deadlineNs - System.nanoTime())
        );
    }

    private PublicPersistenceShutdownReport report(
            PublicPersistenceShutdownReport.Status status,
            SqliteKernelShutdownReport kernelReport,
            Throwable failure
    ) {
        return new PublicPersistenceShutdownReport(
                status,
                workflows.outstanding(),
                kernelReport,
                failure
        );
    }
}
