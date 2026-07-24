package com.alechilles.alecstamework;

import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.companion.coop.runtime
        .TameworkCoopCaptureReceiptsComponent;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.LoadedNpcIdentityBootstrapService;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.persistence
        .FreeCompanionRestorationAuthor;
import com.alechilles.alecstamework.items.persistence
        .PositiveEvidenceDormantAuthor;
import com.alechilles.alecstamework.items.persistence
        .SpawnerCaptureAuthor;
import com.alechilles.alecstamework.items.persistence
        .SpawnerCapturedArtifactReleaseAuthor;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopAuthor;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopProjectionView;
import com.alechilles.alecstamework.npc.components
        .TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.lifecycle
        .TameworkEventRegistrationSupport;
import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import com.alechilles.alecstamework.persistence.TameworkDataPathService;
import com.alechilles.alecstamework.persistence.compensation.runtime
        .HytaleRefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.control
        .PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.control
        .PersistenceStartupReport;
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
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import javax.annotation.Nonnull;

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
    private final PersistenceDomainFacades facades;
    private final CommandLinkedNpcStateSnapshotService snapshots;
    private final SpawnerCaptureAuthor captureAuthor;
    private final SpawnerCapturedArtifactReleaseAuthor releaseAuthor;
    private final FreeCompanionRestorationAuthor restorationAuthor;
    private final PositiveEvidenceDormantAuthor dormantAuthor;
    private final DirectLiveCoopAuthor directLiveCoopAuthor;
    private final DirectLiveCoopProjectionView directLiveCoopProjections;

    private TameworkPersistenceComposition(
            HytaleLogger logger,
            LoadedNpcIdentityBootstrapService identityBootstrap,
            PersistenceBootstrap bootstrap,
            PersistenceDomainFacades facades,
            CommandLinkedNpcStateSnapshotService snapshots,
            SpawnerCaptureAuthor captureAuthor,
            SpawnerCapturedArtifactReleaseAuthor releaseAuthor,
            FreeCompanionRestorationAuthor restorationAuthor,
            PositiveEvidenceDormantAuthor dormantAuthor,
            DirectLiveCoopAuthor directLiveCoopAuthor,
            DirectLiveCoopProjectionView directLiveCoopProjections
    ) {
        this.logger = logger;
        this.identityBootstrap = identityBootstrap;
        this.bootstrap = bootstrap;
        this.facades = facades;
        this.snapshots = snapshots;
        this.captureAuthor = captureAuthor;
        this.releaseAuthor = releaseAuthor;
        this.restorationAuthor = restorationAuthor;
        this.dormantAuthor = dormantAuthor;
        this.directLiveCoopAuthor = directLiveCoopAuthor;
        this.directLiveCoopProjections = directLiveCoopProjections;
    }

    /**
     * Creates the production composition and starts the sealed world-evidence
     * handoff without exposing identity-bootstrap mechanics to Tamework.
     */
    @Nonnull
    static TameworkPersistenceComposition create(
            @Nonnull Tamework plugin,
            @Nonnull TameworkComponentRegistrar.RegisteredComponents components,
            @Nonnull TameworkEventBus events
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
                plugin.getDataDirectory(),
                plugin.getLogger(),
                events,
                identityBootstrap,
                identityIndex,
                components.coopCaptureReceipts(),
                components.persistenceRetirement()
        );
        boolean registered = TameworkEventRegistrationSupport.registerGlobal(
                plugin,
                StartWorldEvent.class,
                event -> {
                    identityBootstrap.onStartWorld(event);
                    composition.resumeAfterWorldEvidence();
                },
                "replacement persistence identity bootstrap"
        );
        if (registered) {
            identityBootstrap.bootstrapUniverse();
        }
        TameworkDormantPersistenceRegistration.register(
                plugin, components, composition.dormantAuthor()
        );
        composition.resumeAfterWorldEvidence();
        return composition;
    }

    /**
     * Opens the replacement target through canonical-load readiness and builds
     * every released persistence author over the same facade bundle.
     */
    @Nonnull
    static TameworkPersistenceComposition open(
            @Nonnull Path pluginDataDirectory,
            @Nonnull HytaleLogger logger,
            @Nonnull TameworkEventBus events,
            @Nonnull LoadedNpcIdentityBootstrapService identityBootstrap,
            @Nonnull LoadedNpcIdentityIndex identityIndex,
            @Nonnull ComponentType<
                    ChunkStore,
                    TameworkCoopCaptureReceiptsComponent
                    > coopReceipts,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent
                    > retirement
    ) {
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
                        coopReceipts,
                        retirement
                )
        );
        PersistenceStartupReport initial = bootstrap.start()
                .toCompletableFuture()
                .join();
        requireProjectionsBuilt(initial, bootstrap);
        PersistenceDomainFacades facades = bootstrap.facades();
        TameworkPersistenceAuthors.Bundle authors =
                TameworkPersistenceAuthors.create(
                        logger,
                        events,
                        identityIndex,
                        facades
                );
        return new TameworkPersistenceComposition(
                logger,
                identityBootstrap,
                bootstrap,
                facades,
                authors.snapshots(),
                authors.captureAuthor(),
                authors.releaseAuthor(),
                authors.restorationAuthor(),
                authors.dormantAuthor(),
                authors.directLiveCoopAuthor(),
                authors.directLiveCoopProjections()
        );
    }

    private static PublicPersistenceRuntimeConfiguration configuration(
            TameworkDataPathLayout paths,
            TameworkEventBus events,
            LoadedNpcIdentityBootstrapService identityBootstrap,
            LoadedNpcIdentityIndex identityIndex,
            ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent>
                    coopReceipts,
            ComponentType<EntityStore, TameworkPersistenceRetirementComponent>
                    retirement
    ) {
        return new PublicPersistenceRuntimeConfiguration(
                paths.targetDirectory(),
                paths.persistenceSourceDirectories(),
                "tamework-" + UUID.randomUUID(),
                System::currentTimeMillis,
                new HytaleRefundDeliveryBoundary(),
                events::publishProfileChanged,
                HytalePersistenceLiveBoundariesFactory.create(
                        coopReceipts,
                        retirement
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
                    CompletionStage<PersistenceStartupReport> resumed =
                            bootstrap.start();
                    resumed.whenComplete((report, startupFailure) ->
                            logStartup(report, startupFailure));
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

    /** Runs the bounded teardown protocol and returns its exact outcome. */
    @Nonnull
    PublicPersistenceShutdownReport shutdown() {
        return bootstrap.shutdown(SHUTDOWN_TIMEOUT);
    }

    @Override
    public void close() {
        shutdown();
    }

}
