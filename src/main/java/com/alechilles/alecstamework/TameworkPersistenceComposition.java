package com.alechilles.alecstamework;

import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.api.internal
        .ReplacementFeatureApiDependencies;
import com.alechilles.alecstamework.companion.capture.runtime
        .TameworkCaptureSourceReceiptsComponent;
import com.alechilles.alecstamework.companion.coop.runtime
        .TameworkCoopCaptureReceiptsComponent;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.population
        .PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.LoadedNpcIdentityBootstrapService;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.persistence
        .FreeCompanionRestorationAuthor;
import com.alechilles.alecstamework.items.persistence
        .PositiveEvidenceDormantAuthor;
import com.alechilles.alecstamework.items.persistence
        .ReplacementProfileSnapshotSink;
import com.alechilles.alecstamework.items.persistence
        .SpawnerCaptureAuthor;
import com.alechilles.alecstamework.items.persistence
        .SpawnerCapturedArtifactReleaseAuthor;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkEvidenceSource;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopAuthor;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopProjectionView;
import com.alechilles.alecstamework.items.persistence.checkpoint.ExactCheckpointCompanionRecallRecovery;
import com.alechilles.alecstamework.items.persistence.checkpoint.ReplacementCompanionEntityCheckpointSink;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceDrainResult;
import com.alechilles.alecstamework.npc.components
        .TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.lifecycle
        .TameworkEventRegistrationSupport;
import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import com.alechilles.alecstamework.persistence.TameworkDataPathService;
import com.alechilles.alecstamework.persistence.activation
        .TameworkPersistenceActivationEvidence;
import com.alechilles.alecstamework.persistence.activation
        .TameworkPersistenceActivationGate;
import com.alechilles.alecstamework.persistence.compensation.runtime
        .HytaleRefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.control
        .PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.control
        .PersistenceStartupReport;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics
        .PersistenceDiagnosticExporter;
import com.alechilles.alecstamework.persistence.runtime
        .HytalePersistenceLiveBoundariesFactory;
import com.alechilles.alecstamework.persistence.runtime
        .HytalePublicPersistenceWorldReconciliation;
import com.alechilles.alecstamework.persistence.runtime
        .PersistenceDiagnosticsReader;
import com.alechilles.alecstamework.persistence.runtime
        .PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime
        .PersistenceDomainFacades;
import com.alechilles.alecstamework.persistence.runtime
        .PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime
        .PublicPersistenceShutdownReport;
import com.alechilles.alecstamework.persistence.runtime.player
        .TameworkInventoryOperationReceiptsComponent;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeModule;
import com.alechilles.alecstamework.runtime.TameworkRuntimeParticipantRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns the single bootstrap and released gameplay persistence boundaries.
 *
 * <p>Tamework composes this object once. No repository, connection, migration,
 * queue, or recovery implementation crosses this boundary.</p>
 */
final class TameworkPersistenceComposition implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);

    private final HytaleLogger logger;
    private final LoadedNpcIdentityBootstrapService identityBootstrap;
    private final PersistenceBootstrap bootstrap;
    private final PersistenceDiagnosticExporter diagnosticsExporter;
    private final PersistenceDomainFacades facades;
    private final CommandLinkedNpcStateSnapshotService snapshots;
    private final ReplacementProfileSnapshotSink profileSink;
    private final ReplacementCompanionEntityCheckpointSink checkpointSink;
    private final SpawnerCaptureAuthor captureAuthor;
    private final SpawnerCapturedArtifactReleaseAuthor releaseAuthor;
    private final FreeCompanionRestorationAuthor restorationAuthor;
    private final PositiveEvidenceDormantAuthor dormantAuthor;
    private final DirectLiveCoopAuthor directLiveCoopAuthor;
    private final DirectLiveCoopProjectionView directLiveCoopProjections;
    private final ExactCheckpointCompanionRecallRecovery exactRecallRecovery;
    private final TameworkRestoredFeatureComposition restoredFeatures;
    private final PersistenceStartupWorldEvidenceResumer startupResumer;

    private TameworkPersistenceComposition(
            HytaleLogger logger,
            LoadedNpcIdentityBootstrapService identityBootstrap,
            PersistenceBootstrap bootstrap,
            PersistenceDiagnosticExporter diagnosticsExporter,
            PersistenceDomainFacades facades,
            CommandLinkedNpcStateSnapshotService snapshots,
            ReplacementProfileSnapshotSink profileSink,
            ReplacementCompanionEntityCheckpointSink checkpointSink,
            SpawnerCaptureAuthor captureAuthor,
            SpawnerCapturedArtifactReleaseAuthor releaseAuthor,
            FreeCompanionRestorationAuthor restorationAuthor,
            PositiveEvidenceDormantAuthor dormantAuthor,
            DirectLiveCoopAuthor directLiveCoopAuthor,
            DirectLiveCoopProjectionView directLiveCoopProjections,
            ExactCheckpointCompanionRecallRecovery exactRecallRecovery,
            TameworkRestoredFeatureComposition restoredFeatures
    ) {
        this.logger = logger;
        this.identityBootstrap = identityBootstrap;
        this.bootstrap = bootstrap;
        this.diagnosticsExporter = diagnosticsExporter;
        this.facades = facades;
        this.snapshots = snapshots;
        this.profileSink = Objects.requireNonNull(
                profileSink, "profileSink"
        );
        this.checkpointSink = Objects.requireNonNull(
                checkpointSink, "checkpointSink"
        );
        this.captureAuthor = captureAuthor;
        this.releaseAuthor = releaseAuthor;
        this.restorationAuthor = restorationAuthor;
        this.dormantAuthor = dormantAuthor;
        this.directLiveCoopAuthor = directLiveCoopAuthor;
        this.directLiveCoopProjections = directLiveCoopProjections;
        this.exactRecallRecovery = exactRecallRecovery;
        this.restoredFeatures = Objects.requireNonNull(
                restoredFeatures, "restoredFeatures"
        );
        this.startupResumer = new PersistenceStartupWorldEvidenceResumer(
                bootstrap,
                this::logStartup
        );
    }

    /**
     * Creates the production composition and starts the sealed world-evidence
     * handoff without exposing identity-bootstrap mechanics to Tamework.
     */
    @Nonnull
    static TameworkPersistenceComposition create(
            @Nonnull Tamework plugin,
            @Nonnull TameworkComponentRegistrar.RegisteredComponents components,
            @Nonnull TameworkEventBus events,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CommandItemRegistry commandItems,
            @Nonnull PopulationGroupConfigRegistry populationGroups
    ) {
        return createInternal(
                plugin,
                components,
                events,
                itemFeatures,
                commandItems,
                populationGroups,
                null,
                null,
                null
        );
    }

    /**
     * Creates persistence only when the frozen plan or durable evidence needs
     * the generic authority.
     *
     * <p>Read-only evidence is never upgraded to a writer. A missing database
     * is allowed when active production content requires a new authority.</p>
     */
    @Nullable
    static TameworkPersistenceComposition createIfActive(
            @Nonnull Tamework plugin,
            @Nonnull TameworkComponentRegistrar.RegisteredComponents components,
            @Nonnull TameworkEventBus events,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CommandItemRegistry commandItems,
            @Nonnull PopulationGroupConfigRegistry populationGroups,
            @Nonnull TameworkRuntimeActivationPlan activationPlan,
            @Nonnull TameworkPersistenceActivationEvidence activationEvidence,
            @Nonnull TameworkRuntimeParticipantRegistry runtimeParticipants
    ) {
        if (!TameworkPersistenceActivationGate.shouldConstruct(
                activationPlan,
                activationEvidence,
                TameworkRuntimeModule.GENERIC_PERSISTENCE
        )) {
            return null;
        }
        return createInternal(
                plugin,
                components,
                events,
                itemFeatures,
                commandItems,
                populationGroups,
                activationPlan,
                activationEvidence,
                runtimeParticipants
        );
    }

    private static TameworkPersistenceComposition createInternal(
            @Nonnull Tamework plugin,
            @Nonnull TameworkComponentRegistrar.RegisteredComponents components,
            @Nonnull TameworkEventBus events,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CommandItemRegistry commandItems,
            @Nonnull PopulationGroupConfigRegistry populationGroups,
            @Nullable TameworkRuntimeActivationPlan activationPlan,
            @Nullable TameworkPersistenceActivationEvidence activationEvidence,
            @Nullable TameworkRuntimeParticipantRegistry runtimeParticipants
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(components, "components");
        LoadedNpcIdentityIndex identityIndex = new LoadedNpcIdentityIndex();
        LoadedNpcIdentityBootstrapService identityBootstrap =
                new LoadedNpcIdentityBootstrapService(
                        identityIndex,
                        plugin.getLogger()
                );
        TameworkPersistenceComposition composition = open(
                plugin,
                plugin.getDataDirectory(),
                plugin.getLogger(),
                events,
                identityBootstrap,
                identityIndex,
                components.captureSourceReceipts(),
                components.coopCaptureReceipts(),
                components.persistenceRetirement(),
                components.inventoryOperationReceipts(),
                itemFeatures,
                commandItems,
                populationGroups,
                activationPlan,
                activationEvidence,
                runtimeParticipants
        );
        AtomicBoolean startupWorldsLoaded = new AtomicBoolean();
        Runnable startWorldRegistration = () -> TameworkEventRegistrationSupport.registerGlobal(
                plugin, StartWorldEvent.class, event -> {
                    identityBootstrap.onStartWorld(event);
                    if (startupWorldsLoaded.get()) {
                        composition.resumeAfterWorldEvidence();
                    }
                }, "replacement persistence identity bootstrap");
        Runnable worldsLoadedRegistration = () -> TameworkEventRegistrationSupport.registerGlobal(
                plugin, AllWorldsLoadedEvent.class, ignored -> {
                    startupWorldsLoaded.set(true);
                    identityBootstrap.bootstrapUniverse();
                    composition.resumeAfterWorldEvidence();
                }, "replacement persistence startup-world seal");
        if (runtimeParticipants == null) {
            startWorldRegistration.run();
            worldsLoadedRegistration.run();
        } else {
            runtimeParticipants.listener(TameworkRuntimeModule.GENERIC_PERSISTENCE,
                    "replacement-persistence-start-world", startWorldRegistration);
            runtimeParticipants.listener(TameworkRuntimeModule.GENERIC_PERSISTENCE,
                    "replacement-persistence-worlds-loaded", worldsLoadedRegistration);
        }
        if (activationPlan == null) {
            TameworkDormantPersistenceRegistration.register(
                    plugin, components, composition.dormantAuthor()
            );
        } else {
            if (runtimeParticipants == null) {
                TameworkDormantPersistenceRegistration.registerIfActive(
                        plugin, components, composition.dormantAuthor(), activationPlan);
            } else {
                TameworkDormantPersistenceRegistration.declareIfActive(
                        plugin, components, composition.dormantAuthor(), activationPlan,
                        runtimeParticipants);
            }
        }
        return composition;
    }

    /**
     * Opens the replacement target through canonical-load readiness and builds
     * every released persistence author over the same facade bundle.
     */
    @Nonnull
    static TameworkPersistenceComposition open(
            @Nonnull Tamework plugin,
            @Nonnull Path pluginDataDirectory,
            @Nonnull HytaleLogger logger,
            @Nonnull TameworkEventBus events,
            @Nonnull LoadedNpcIdentityBootstrapService identityBootstrap,
            @Nonnull LoadedNpcIdentityIndex identityIndex,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkCaptureSourceReceiptsComponent
                    > captureSourceReceipts,
            @Nonnull ComponentType<
                    ChunkStore,
                    TameworkCoopCaptureReceiptsComponent
                    > coopReceipts,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent
                    > retirement,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent
            > inventoryReceipts,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CommandItemRegistry commandItems,
            @Nonnull PopulationGroupConfigRegistry populationGroups
    ) {
        return open(
                plugin,
                pluginDataDirectory,
                logger,
                events,
                identityBootstrap,
                identityIndex,
                captureSourceReceipts,
                coopReceipts,
                retirement,
                inventoryReceipts,
                itemFeatures,
                commandItems,
                populationGroups,
                null,
                null,
                null
        );
    }

    @Nonnull
    private static TameworkPersistenceComposition open(
            @Nonnull Tamework plugin,
            @Nonnull Path pluginDataDirectory,
            @Nonnull HytaleLogger logger,
            @Nonnull TameworkEventBus events,
            @Nonnull LoadedNpcIdentityBootstrapService identityBootstrap,
            @Nonnull LoadedNpcIdentityIndex identityIndex,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkCaptureSourceReceiptsComponent
                    > captureSourceReceipts,
            @Nonnull ComponentType<
                    ChunkStore,
                    TameworkCoopCaptureReceiptsComponent
                    > coopReceipts,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent
                    > retirement,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent
                    > inventoryReceipts,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CommandItemRegistry commandItems,
            @Nonnull PopulationGroupConfigRegistry populationGroups,
            @Nullable TameworkRuntimeActivationPlan activationPlan,
            @Nullable TameworkPersistenceActivationEvidence activationEvidence,
            @Nullable TameworkRuntimeParticipantRegistry runtimeParticipants
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(identityBootstrap, "identityBootstrap");
        Objects.requireNonNull(identityIndex, "identityIndex");
        TameworkDataPathLayout paths = new TameworkDataPathService(logger)
                .resolveAndInitializeDataPathLayout(pluginDataDirectory);
        PersistenceBootstrap bootstrap = new PersistenceBootstrap(
                configuration(
                        paths,
                        events,
                        identityBootstrap,
                        identityIndex,
                        captureSourceReceipts,
                        coopReceipts,
                        retirement,
                        inventoryReceipts
                )
        );
        PersistenceStartupReport initial = bootstrap.start()
                .toCompletableFuture()
                .join();
        requireProjectionsBuilt(initial, bootstrap);
        PersistenceDomainFacades facades = bootstrap.facades();
        PersistenceDiagnosticExporter diagnosticsExporter =
                new PersistenceDiagnosticExporter(
                        paths.targetDirectory(),
                        bootstrap.diagnosticsReader()
                );
        TameworkPersistenceAuthors.Bundle authors =
                TameworkPersistenceAuthors.create(
                        logger,
                        events,
                        identityIndex,
                        facades,
                        retirement
                );
        TameworkRestoredFeatureComposition restoredFeatures;
        try {
            restoredFeatures = activationPlan == null
                    ? TameworkRestoredFeatureComposition.create(
                            plugin,
                            paths.targetDirectory(),
                            bootstrap,
                            facades,
                            populationGroups,
                            itemFeatures,
                            commandItems
                    )
                    : Objects.requireNonNull(
                            TameworkRestoredFeatureComposition.createIfActive(
                                    plugin,
                                    paths.targetDirectory(),
                                    bootstrap,
                                    facades,
                                    populationGroups,
                                    itemFeatures,
                                    commandItems,
                                    activationPlan,
                                    Objects.requireNonNull(
                                            activationEvidence,
                                            "Active persistence requires activation evidence"
                                    ),
                                    runtimeParticipants
                            ),
                            "Active generic persistence requires restored features"
                    );
        } catch (RuntimeException | Error failure) {
            bootstrap.close();
            throw failure;
        }
        bootstrap.bindLifecycleAdmission(restoredFeatures.lifecycleAdmission());
        TameworkPersistenceComposition composition =
                new TameworkPersistenceComposition(
                logger,
                identityBootstrap,
                bootstrap,
                diagnosticsExporter,
                facades,
                authors.snapshots(),
                authors.profileSink(),
                authors.checkpointSink(),
                authors.captureAuthor(),
                authors.releaseAuthor(),
                authors.restorationAuthor(),
                authors.dormantAuthor(),
                authors.directLiveCoopAuthor(),
                authors.directLiveCoopProjections(),
                authors.exactRecallRecovery(),
                restoredFeatures
        );
        if (initial.complete()) {
            restoredFeatures.activateMutationReady();
        }
        return composition;
    }

    private static PublicPersistenceRuntimeConfiguration configuration(
            TameworkDataPathLayout paths,
            TameworkEventBus events,
            LoadedNpcIdentityBootstrapService identityBootstrap,
            LoadedNpcIdentityIndex identityIndex,
            ComponentType<EntityStore, TameworkCaptureSourceReceiptsComponent>
                    captureSourceReceipts,
            ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent>
                    coopReceipts,
            ComponentType<EntityStore, TameworkPersistenceRetirementComponent>
                    retirement,
            ComponentType<EntityStore, TameworkInventoryOperationReceiptsComponent>
                    inventoryReceipts
    ) {
        return new PublicPersistenceRuntimeConfiguration(
                paths.targetDirectory(),
                paths.persistenceSourceDirectories(),
                "tamework-" + UUID.randomUUID(),
                System::currentTimeMillis,
                new HytaleRefundDeliveryBoundary(),
                events::publishProfileChanged,
                events::publishPersistenceEvent,
                HytalePersistenceLiveBoundariesFactory.create(
                        captureSourceReceipts,
                        coopReceipts,
                        retirement,
                        inventoryReceipts
                ),
                HytalePublicPersistenceWorldReconciliation.factory(
                        identityBootstrap,
                        identityIndex,
                        System::currentTimeMillis
                ),
                SHUTDOWN_TIMEOUT
        );
    }

    private static void requireProjectionsBuilt(
            PersistenceStartupReport report,
            PersistenceBootstrap bootstrap
    ) {
        if (report.completedNodes().contains(
                PersistenceStartupNode.BUILD_PROJECTIONS
        )) {
            return;
        }
        bootstrap.close();
        throw new IllegalStateException(
                "Replacement persistence failed before projection build: "
                        + report.detail()
        );
    }

    /** Resumes the startup graph once the world-thread identity scan is sealed. */
    void resumeAfterWorldEvidence() {
        identityBootstrap.awaitCurrentBootstrap().whenComplete(
                (snapshot, evidenceFailure) -> {
                    if (evidenceFailure != null) {
                        logger.at(Level.SEVERE).withCause(evidenceFailure).log(
                                "Replacement persistence world evidence failed."
                        );
                        return;
                    }
                    startupResumer.resume();
                }
        );
    }

    private void logStartup(
            PersistenceStartupReport report,
            Throwable failure
    ) {
        if (failure != null) {
            logger.at(Level.SEVERE).withCause(failure).log(
                    "Replacement persistence startup failed."
            );
            return;
        }
        if (report == null || !report.complete()) {
            logger.at(Level.SEVERE).log(
                    "Replacement persistence is not mutation-ready: "
                            + (report == null ? "missing report"
                            : report.detail())
            );
            return;
        }
        restoredFeatures.activateMutationReady();
        logger.at(Level.INFO).log(
                "Replacement persistence is mutation-ready."
        );
    }

    @Nonnull
    Path dataDirectory() {
        return bootstrap.operationalStatus().dataDirectory();
    }

    @Nonnull
    PersistenceDiagnosticsReader diagnosticsReader() {
        return bootstrap.diagnosticsReader();
    }

    @Nonnull
    PersistenceDiagnosticExporter diagnosticsExporter() {
        return diagnosticsExporter;
    }

    /** Aggregates bonded diagnostics without owning bonded lifecycle/readiness. */
    @Nonnull
    AutoCloseable registerBondedDiagnostics(
            @Nonnull BondedCompanionDiagnosticContributor contributor
    ) {
        return diagnosticsExporter.registerBondedContributor(contributor);
    }

    @Nonnull
    PersistenceBootstrap persistence() {
        return bootstrap;
    }

    @Nonnull
    PersistenceDomainFacades facades() {
        return facades;
    }

    @Nonnull
    CommandLinkedNpcStateSnapshotService snapshots() {
        return snapshots;
    }

    @Nonnull
    ReplacementCompanionEntityCheckpointSink checkpointSink() {
        return checkpointSink;
    }

    ExactCheckpointCompanionRecallRecovery exactRecallRecovery() {
        return exactRecallRecovery;
    }

    @Nonnull
    SpawnerCaptureAuthor captureAuthor() {
        return captureAuthor;
    }

    @Nonnull
    SpawnerCapturedArtifactReleaseAuthor releaseAuthor() {
        return releaseAuthor;
    }

    @Nonnull
    FreeCompanionRestorationAuthor restorationAuthor() {
        return restorationAuthor;
    }

    @Nonnull
    PositiveEvidenceDormantAuthor dormantAuthor() {
        return dormantAuthor;
    }

    @Nonnull
    DirectLiveCoopAuthor directLiveCoopAuthor() {
        return directLiveCoopAuthor;
    }

    @Nonnull
    DirectLiveCoopProjectionView directLiveCoopProjections() {
        return directLiveCoopProjections;
    }

    @Nonnull
    ReplacementFeatureApiDependencies featureApiDependencies() {
        return restoredFeatures.apiDependencies();
    }

    @Nonnull
    SpawnerTameAndLinkEvidenceSource tameAndLinkEvidence() {
        return restoredFeatures.tameAndLinkEvidence();
    }

    /** Runs the bounded teardown protocol and returns its exact outcome. */
    @Nonnull
    PublicPersistenceShutdownReport shutdown() {
        startupResumer.close();
        restoredFeatures.close();
        long startedAtNanos = System.nanoTime();
        MaintenanceDrainResult profileDrain = profileSink.shutdown(
                remainingShutdownTimeout(startedAtNanos)
        );
        if (!profileDrain.drained()) {
            logger.at(Level.WARNING).log(
                    "Profile maintenance did not drain before persistence "
                            + "shutdown deadline: pendingKeys=%d, "
                            + "pendingWork=%d, inFlight=%d",
                    profileDrain.pendingKeys(),
                    profileDrain.pendingWork(),
                    profileDrain.inFlightWork()
            );
        }
        MaintenanceDrainResult checkpointDrain = checkpointSink.shutdown(
                remainingShutdownTimeout(startedAtNanos)
        );
        if (!checkpointDrain.drained()) {
            logger.at(Level.WARNING).log(
                    "Checkpoint maintenance did not drain before persistence "
                            + "shutdown deadline: pendingKeys=%d, "
                            + "pendingWork=%d, inFlight=%d",
                    checkpointDrain.pendingKeys(),
                    checkpointDrain.pendingWork(),
                    checkpointDrain.inFlightWork()
            );
        }
        return bootstrap.shutdown(remainingShutdownTimeout(startedAtNanos));
    }

    private Duration remainingShutdownTimeout(long startedAtNanos) {
        long elapsed = System.nanoTime() - startedAtNanos;
        if (elapsed <= 0) {
            return SHUTDOWN_TIMEOUT;
        }
        long total;
        try {
            total = SHUTDOWN_TIMEOUT.toNanos();
        } catch (ArithmeticException overflow) {
            return SHUTDOWN_TIMEOUT;
        }
        long remaining = total - elapsed;
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    @Override
    public void close() {
        shutdown();
    }

}
