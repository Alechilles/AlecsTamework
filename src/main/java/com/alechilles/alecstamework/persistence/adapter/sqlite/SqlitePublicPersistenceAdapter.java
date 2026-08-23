package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptCooldownIndex;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionIndex;
import com.alechilles.alecstamework.companion.command.CommandRosterProjectionIndex;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonProjectionIndex;
import com.alechilles.alecstamework.companion.population.OwnerPopulationProjectionIndex;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupProjectionIndex;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningProjectionIndex;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.facade
        .ReplacementPublicApiEventSink;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionPublicationContext;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime
        .PersistenceLifecycleAdmissionGateway;
import com.alechilles.alecstamework.persistence.runtime.PersistenceThroughputMetrics;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Typed SQLite adapter boundary for all released persistence behavior.
 *
 * <p>Gameplay receives operations, queries, and projections only. Connection-bound
 * stores, writer lanes, and transaction infrastructure remain package-private.</p>
 */
public final class SqlitePublicPersistenceAdapter {
    private final SqlitePublicProjectionSet projections;
    private final SqlitePublicOperationSet publicOperations;
    private final SqlitePublicOperationSet recoveryOperations;
    private final SqliteOperationRecoveryCoordinator recovery;
    private final SqlitePublicStartupGateway startup;
    private final SqlitePublicControlGateway control;
    private final SqlitePublicDiagnosticsReader diagnostics;
    private final SqliteCompanionProfileReader profiles;
    private final SqliteCompanionLifecycleReader lifecycles;
    private final SqliteCompanionSnapshotReader snapshots;
    private final SqliteCompanionCoopReader coops;
    private final SqliteProfileExtensionReader extensions;
    private final SqlitePopulationGroupReader populationGroups;
    private final SqliteCommandRosterReader commandRosters;
    private final SqliteTimedSummonLeaseReader timedSummons;
    private final SqliteProvisioningReader provisioning;
    private final SqliteOperationReader operationReader;
    private final SqliteContainmentReader containmentReader;
    private final LongSupplier clock;
    private final PersistenceFeatureRegistry registry;

    public SqlitePublicPersistenceAdapter(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull SqlitePersistenceKernel kernel,
            @Nonnull PersistenceOperationAdmissionGate admission,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nonnull Consumer<NpcProfileChangedEvent> profileListener
    ) {
        this(
                registry,
                kernel,
                admission,
                clock,
                refunds,
                profileListener,
                ReplacementPublicApiEventSink.NO_OP,
                PersistenceThroughputMetrics.NO_OP
        );
    }

    /**
     * Builds the adapter with the checkpointed semantic-event destination.
     */
    public SqlitePublicPersistenceAdapter(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull SqlitePersistenceKernel kernel,
            @Nonnull PersistenceOperationAdmissionGate admission,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nonnull Consumer<NpcProfileChangedEvent> profileListener,
            @Nonnull ReplacementPublicApiEventSink publicEventSink
    ) {
        this(
                registry,
                kernel,
                admission,
                clock,
                refunds,
                profileListener,
                publicEventSink,
                PersistenceThroughputMetrics.NO_OP
        );
    }

    /** Builds the adapter with passive throughput measurements. */
    public SqlitePublicPersistenceAdapter(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull SqlitePersistenceKernel kernel,
            @Nonnull PersistenceOperationAdmissionGate admission,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nonnull Consumer<NpcProfileChangedEvent> profileListener,
            @Nonnull ReplacementPublicApiEventSink publicEventSink,
            @Nonnull PersistenceThroughputMetrics throughputMetrics
    ) {
        if (registry == null || kernel == null || admission == null
                || clock == null || refunds == null || profileListener == null
                || publicEventSink == null || throughputMetrics == null) {
            throw new IllegalArgumentException(
                    "Public persistence adapter dependencies are required"
            );
        }
        this.clock = clock;
        this.registry = registry;
        projections = new SqlitePublicProjectionSet(
                registry,
                kernel,
                clock,
                profileListener,
                publicEventSink,
                throughputMetrics
        );
        publicOperations = new SqlitePublicOperationSet(
                registry,
                kernel,
                projections,
                admission,
                clock,
                refunds,
                ProjectionPublicationContext.LIVE_COMMIT
        );
        recoveryOperations = new SqlitePublicOperationSet(
                registry,
                kernel,
                projections,
                PersistenceOperationAdmissionGate.allowAll(),
                clock,
                refunds,
                ProjectionPublicationContext.RECOVERY_CONVERGENCE,
                publicOperations.lifecycleAdmission()
        );
        recovery = new SqliteOperationRecoveryCoordinator(
                registry.operationDefinitions(),
                kernel.reads(),
                kernel.units()
        );
        startup = new SqlitePublicStartupGateway(kernel.reads());
        control = new SqlitePublicControlGateway(
                registry, kernel.units(), clock
        );
        diagnostics = new SqlitePublicDiagnosticsReader(
                registry, kernel.reads()
        );
        profiles = new SqliteCompanionProfileReader(kernel.reads());
        lifecycles = new SqliteCompanionLifecycleReader(kernel.reads());
        snapshots = new SqliteCompanionSnapshotReader(kernel.reads());
        coops = new SqliteCompanionCoopReader(kernel.reads());
        extensions = new SqliteProfileExtensionReader(kernel.reads());
        populationGroups = new SqlitePopulationGroupReader(kernel.reads());
        commandRosters = new SqliteCommandRosterReader(kernel.reads());
        timedSummons = new SqliteTimedSummonLeaseReader(kernel.reads());
        provisioning = new SqliteProvisioningReader(kernel.reads());
        operationReader = new SqliteOperationReader(kernel.reads());
        containmentReader = new SqliteContainmentReader(kernel.reads());
    }

    /** Binds managed lifecycle authoring once before mutation readiness. */
    public void bindLifecycleAdmission(
            @Nonnull PersistenceLifecycleAdmissionGateway gateway
    ) {
        publicOperations.bindLifecycleAdmission(gateway);
    }

    @Nonnull
    public SqliteCompanionProfileOperations profileOperations() {
        return publicOperations.profiles();
    }

    /**
     * Submits the sole startup reconciliation mutation through the shared
     * operation protocol without opening general public mutation admission.
     */
    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission
    reconcileProfileAtStartup(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionProfileMutation.StartupReconciliation reconciliation
    ) {
        return recoveryOperations.profiles().submit(
                operationId,
                idempotencyKey,
                reconciliation
        );
    }

    @Nonnull
    public SqliteCompanionAliasRotationOperations aliasOperations() {
        return publicOperations.aliases();
    }

    @Nonnull
    public SqliteOwnerPopulationTransitionOperations
    ownerPopulationOperations() {
        return publicOperations.ownerPopulation();
    }

    @Nonnull
    public SqliteOwnerPopulationReconciliationOperations
    ownerPopulationReconciliationOperations() {
        return publicOperations.ownerPopulationReconciliation();
    }

    @Nonnull
    public SqlitePopulationGroupAssignmentOperations
    populationGroupOperations() {
        return publicOperations.populationGroups();
    }

    @Nonnull
    public PopulationDomainAdmissionOperation
    populationDomainAdmissionOperations() {
        return publicOperations.populationDomains();
    }

    @Nonnull
    public SqliteCommandRosterMembershipOperations
    commandRosterOperations() {
        return publicOperations.commandRosters();
    }

    @Nonnull
    public SqliteCommandRosterTransitionOperations
    commandRosterTransitionOperations() {
        return publicOperations.commandTransitions();
    }

    @Nonnull
    public SqliteTimedSummonLeaseOperations timedSummonOperations() {
        return publicOperations.timedSummons();
    }

    @Nonnull
    public SqliteTimedSummonTransitionOperations
    timedSummonTransitionOperations() {
        return publicOperations.timedTransitions();
    }

    @Nonnull
    public SqliteCompanionProvisioningOperations provisioningOperations() {
        return publicOperations.provisioning();
    }

    @Nonnull
    public SqliteProvisioningActivationOperations
    provisioningActivationOperations() {
        return publicOperations.provisioningActivations();
    }

    @Nonnull
    public SqliteCompanionCaptureOperations captureOperations() {
        return publicOperations.captures();
    }

    @Nonnull
    public SqliteCompanionCaptureReleaseOperations
    captureReleaseOperations() {
        return publicOperations.captureReleases();
    }

    @Nonnull
    public SqliteCompanionDormantOperations dormantOperations() {
        return publicOperations.dormant();
    }

    @Nonnull
    public SqliteCompanionRestorationOperations restorationOperations() {
        return publicOperations.restorations();
    }

    @Nonnull
    public SqliteCoopSlotOperations coopSlotOperations() {
        return publicOperations.coopSlots();
    }

    @Nonnull
    public SqliteCompanionCoopCaptureOperations coopCaptureOperations() {
        return publicOperations.coopCaptures();
    }

    @Nonnull
    public SqliteCompanionCoopReleaseOperations coopReleaseOperations() {
        return publicOperations.coopReleases();
    }

    @Nonnull
    public SqlitePaidRevivalOperations paidRevivalOperations() {
        return publicOperations.paidRevivals();
    }

    @Nonnull
    public SqliteProfileExtensionOperations extensionOperations() {
        return publicOperations.extensions();
    }

    /** Returns the durable breeding litter job adapter. */
    @Nonnull
    public SqliteBreedingLitterOperations breedingLitterOperations() {
        return publicOperations.breedingLitters();
    }

    @Nonnull
    public SqliteReviveReadyOperations reviveReadyOperations() {
        return publicOperations.reviveReady();
    }

    @Nonnull
    public SqliteCompanionProfileReader profileReader() {
        return profiles;
    }

    @Nonnull
    public SqliteCompanionLifecycleReader lifecycleReader() {
        return lifecycles;
    }

    @Nonnull
    public SqliteCompanionSnapshotReader snapshotReader() {
        return snapshots;
    }

    @Nonnull
    public SqliteCompanionCoopReader coopReader() {
        return coops;
    }

    @Nonnull
    public SqliteProfileExtensionReader extensionReader() {
        return extensions;
    }

    @Nonnull
    public SqlitePopulationGroupReader populationGroupReader() {
        return populationGroups;
    }

    @Nonnull
    public SqliteCommandRosterReader commandRosterReader() {
        return commandRosters;
    }

    @Nonnull
    public SqliteTimedSummonLeaseReader timedSummonReader() {
        return timedSummons;
    }

    @Nonnull
    public SqliteProvisioningReader provisioningReader() {
        return provisioning;
    }

    @Nonnull
    public SqliteOperationReader operationReader() {
        return operationReader;
    }

    /** Returns bounded incident and exact-scope quarantine diagnostics. */
    @Nonnull
    public SqliteContainmentReader containmentReader() {
        return containmentReader;
    }

    @Nonnull
    public CoopResidencyProjectionIndex coopIndex() {
        return projections.coopIndex();
    }

    @Nonnull
    public OwnerPopulationProjectionIndex ownerPopulationIndex() {
        return projections.ownerPopulationIndex();
    }

    @Nonnull
    public PopulationGroupProjectionIndex populationGroupIndex() {
        return projections.populationGroupIndex();
    }

    @Nonnull
    public CommandRosterProjectionIndex commandRosterIndex() {
        return projections.commandRosterIndex();
    }

    @Nonnull
    public TimedSummonProjectionIndex timedSummonIndex() {
        return projections.timedSummonIndex();
    }

    @Nonnull
    public ProvisioningProjectionIndex provisioningIndex() {
        return projections.provisioningIndex();
    }

    @Nonnull
    public CaptureAttemptCooldownIndex captureCooldownIndex() {
        return projections.captureCooldownIndex();
    }

    @Nonnull
    public com.alechilles.alecstamework.api.internal
            .CompanionProfileObserverProjection profileIndex() {
        return projections.profileIndex();
    }

    @Nonnull
    public com.alechilles.alecstamework.companion.extension
            .ProfileExtensionProjectionIndex extensionIndex() {
        return projections.extensionIndex();
    }

    /** Loads the complete canonical startup evidence through the read lane. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<SqlitePublicCanonicalSnapshot>>
    loadCanonical() {
        return startup.loadCanonical();
    }

    /** Synchronizes circuits with the exact descriptor set and returns them. */
    @Nonnull
    public CompletionStage<com.alechilles.alecstamework.persistence.kernel
            .PersistenceTransactionResult<SqlitePublicControlSnapshot>>
    synchronizeControlPlane() {
        return control.synchronize();
    }

    /** Reads sanitized operational evidence on the isolated diagnostic lane. */
    @Nonnull
    public CompletionStage<
            PersistenceReadResult<SqlitePublicDiagnosticsSnapshot>>
    diagnostics() {
        return diagnostics.read();
    }

    /** Rebuilds canonical derived state and catches every registry consumer up. */
    @Nonnull
    public CompletionStage<SqlitePublicProjectionStartupResult>
    buildProjections() {
        return projections.rebuildAndCatchUp(
                profiles,
                coops,
                lifecycles,
                populationGroups,
                commandRosters
        );
    }

    /** Scans and resumes every recoverable operation through typed adapters. */
    @Nonnull
    public CompletionStage<SqlitePublicRecoveryResult> recover(
            @Nonnull PublicPersistenceLiveBoundaries boundaries,
            @Nonnull String workerId
    ) {
        return new SqlitePublicRecoveryDispatcher(
                recovery,
                registry,
                recoveryOperations,
                boundaries,
                clock,
                workerId
        ).recover();
    }

    SqlitePublicProjectionSet projections() {
        return projections;
    }

    SqlitePublicOperationSet publicOperations() {
        return publicOperations;
    }

    SqlitePublicOperationSet recoveryOperations() {
        return recoveryOperations;
    }

    SqliteOperationRecoveryCoordinator recovery() {
        return recovery;
    }

    SqlitePublicStartupGateway startup() {
        return startup;
    }
}
