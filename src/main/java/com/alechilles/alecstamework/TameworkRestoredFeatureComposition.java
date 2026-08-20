package com.alechilles.alecstamework;

import com.alechilles.alecstamework.api.internal
        .ReplacementFeatureApiDependencies;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.population
        .PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.items.CapturedItemCoopRuntime;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.coop.CapturedItemCoopAuthor;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkEvidenceSource;
import com.alechilles.alecstamework.items.persistence
        .TameworkFullStateSnapshotReader;
import com.alechilles.alecstamework.items.persistence
        .TameworkSnapshotCodecs;
import com.alechilles.alecstamework.items.persistence
        .TameworkSpawnerTameAndLinkEvidenceSource;
import com.alechilles.alecstamework.persistence.authoring
        .ReplacementFeatureEvidenceAuthors;
import com.alechilles.alecstamework.persistence.authoring
        .TameworkFeaturePolicySource;
import com.alechilles.alecstamework.persistence.authoring.runtime
        .HytaleReplacementFeatureLiveEvidenceSource;
import com.alechilles.alecstamework.persistence.facade
        .ReplacementPersistenceDiagnosticsAdapters;
import com.alechilles.alecstamework.persistence.activation
        .TameworkPersistenceActivationEvidence;
import com.alechilles.alecstamework.persistence.activation
        .TameworkPersistenceActivationGate;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime
        .PersistenceDomainFacades;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeModule;
import com.alechilles.alecstamework.runtime.TameworkRuntimeParticipantRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns restored feature authors, live evidence, and codec-created runtime seams.
 *
 * <p>This composition adds no persistence authority. Every mutation still
 * enters the shared replacement facade bundle, while Hytale state is frozen
 * through one world-thread source and discarded after authoring.</p>
 */
final class TameworkRestoredFeatureComposition implements AutoCloseable {
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final String DIAGNOSTIC_SCOPE_KEY =
            "diagnostics-scope.key";

    private final HytaleLogger logger;
    private final HytaleReplacementFeatureLiveEvidenceSource liveEvidence;
    private final ReplacementFeatureApiDependencies apiDependencies;
    private final AdmissionProviderRegistry admissionProviders;
    private final SpawnerTameAndLinkEvidenceSource tameAndLinkEvidence;
    private final CapturedItemCoopRuntime.Submission capturedItemCoop;
    private final AtomicBoolean capturedItemCoopInstalled =
            new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private TameworkRestoredFeatureComposition(
            HytaleLogger logger,
            HytaleReplacementFeatureLiveEvidenceSource liveEvidence,
            ReplacementFeatureApiDependencies apiDependencies,
            AdmissionProviderRegistry admissionProviders,
            SpawnerTameAndLinkEvidenceSource tameAndLinkEvidence,
            CapturedItemCoopRuntime.Submission capturedItemCoop
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.liveEvidence = Objects.requireNonNull(
                liveEvidence, "liveEvidence"
        );
        this.apiDependencies = Objects.requireNonNull(
                apiDependencies, "apiDependencies"
        );
        this.admissionProviders = Objects.requireNonNull(
                admissionProviders, "admissionProviders"
        );
        this.tameAndLinkEvidence = Objects.requireNonNull(
                tameAndLinkEvidence, "tameAndLinkEvidence"
        );
        this.capturedItemCoop = Objects.requireNonNull(
                capturedItemCoop, "capturedItemCoop"
        );
    }

    /** Builds all restored feature seams over one replacement facade bundle. */
    @Nonnull
    static TameworkRestoredFeatureComposition create(
            @Nonnull Tamework plugin,
            @Nonnull Path persistenceDirectory,
            @Nonnull PersistenceBootstrap bootstrap,
            @Nonnull PersistenceDomainFacades facades,
            @Nonnull PopulationGroupConfigRegistry populationGroups,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CommandItemRegistry commandItems
    ) {
        TameworkRestoredFeatureComposition composition = build(
                plugin, persistenceDirectory, bootstrap, facades,
                populationGroups, itemFeatures, commandItems);
        plugin.getEntityStoreRegistry().registerSystem(
                composition.liveEvidence.trackingSystem());
        return composition;
    }

    private static TameworkRestoredFeatureComposition build(
            Tamework plugin,
            Path persistenceDirectory,
            PersistenceBootstrap bootstrap,
            PersistenceDomainFacades facades,
            PopulationGroupConfigRegistry populationGroups,
            ItemFeatureRegistry itemFeatures,
            CommandItemRegistry commandItems
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(persistenceDirectory, "persistenceDirectory");
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(facades, "facades");
        Objects.requireNonNull(populationGroups, "populationGroups");
        Objects.requireNonNull(itemFeatures, "itemFeatures");
        Objects.requireNonNull(commandItems, "commandItems");

        CoopResidentStateSnapshotService snapshots =
                new CoopResidentStateSnapshotService();
        var snapshotCodecs = TameworkSnapshotCodecs.create();
        HytaleReplacementFeatureLiveEvidenceSource live =
                new HytaleReplacementFeatureLiveEvidenceSource(
                        commandItems, snapshotCodecs, snapshots
                );
        ReplacementFeatureEvidenceAuthors authors =
                new ReplacementFeatureEvidenceAuthors(
                        facades.queries(), populationGroups, live
                );
        ReplacementPersistenceDiagnosticsAdapters diagnostics =
                diagnostics(
                        persistenceDirectory,
                        bootstrap,
                        facades,
                        plugin.getLogger()
        );
        SpawnerTameAndLinkEvidenceSource tameAndLink =
                new TameworkSpawnerTameAndLinkEvidenceSource(
                        facades.queries(),
                        populationGroups,
                        itemFeatures,
                        commandItems,
                        new TameworkFeaturePolicySource(),
                        new TameworkFullStateSnapshotReader(snapshots)
                );
        CapturedItemCoopAuthor coop = new CapturedItemCoopAuthor(facades);
        AdmissionProviderRegistry admissionProviders =
                new AdmissionProviderRegistry(Duration.ofSeconds(2));
        ReplacementFeatureApiDependencies dependencies =
                new ReplacementFeatureApiDependencies(
                        populationGroups,
                        authors.commandRosters(),
                        authors.timedSummoning(),
                        authors.provisioning(),
                        authors.paidRevival(),
                        diagnostics.availability(),
                        diagnostics.incidents(),
                        true,
                        true,
                        null,
                        plugin.getManagedActivityConfigRegistry(),
                        admissionProviders
                );
        return new TameworkRestoredFeatureComposition(
                plugin.getLogger(),
                live,
                dependencies,
                admissionProviders,
                tameAndLink,
                        coop::capture
                );
    }

    /**
     * Builds restored feature seams only for an active generic persistence
     * module. The guard runs before snapshot, author, or tracking-system
     * construction.
     */
    @Nullable
    static TameworkRestoredFeatureComposition createIfActive(
            @Nonnull Tamework plugin,
            @Nonnull Path persistenceDirectory,
            @Nonnull PersistenceBootstrap bootstrap,
            @Nonnull PersistenceDomainFacades facades,
            @Nonnull PopulationGroupConfigRegistry populationGroups,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CommandItemRegistry commandItems,
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
        TameworkRestoredFeatureComposition composition = build(
                plugin, persistenceDirectory, bootstrap, facades,
                populationGroups, itemFeatures, commandItems);
        runtimeParticipants.entitySystem(
                TameworkRuntimeModule.GENERIC_PERSISTENCE,
                "replacement-live-evidence-tracking",
                composition.liveEvidence::trackingSystem
        );
        return composition;
    }

    @Nonnull
    private static ReplacementPersistenceDiagnosticsAdapters diagnostics(
            Path persistenceDirectory,
            PersistenceBootstrap bootstrap,
            PersistenceDomainFacades facades,
            HytaleLogger logger
    ) {
        try {
            return ReplacementPersistenceDiagnosticsAdapters.create(
                    persistenceDirectory.resolve(DIAGNOSTIC_SCOPE_KEY),
                    bootstrap,
                    facades,
                    READ_TIMEOUT
            );
        } catch (Exception failure) {
            logger.at(Level.WARNING).withCause(failure).log(
                    "Persistent diagnostics scope hashing is unavailable; "
                            + "using an installation-session key."
            );
            return ReplacementPersistenceDiagnosticsAdapters.ephemeral(
                    bootstrap, facades, READ_TIMEOUT
            );
        }
    }

    @Nonnull
    ReplacementFeatureApiDependencies apiDependencies() {
        return apiDependencies;
    }

    @Nonnull
    SpawnerTameAndLinkEvidenceSource tameAndLinkEvidence() {
        return tameAndLinkEvidence;
    }

    /** Installs the codec runtime only after the persistence graph is ready. */
    void activateMutationReady() {
        if (closed.get()
                || !capturedItemCoopInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            CapturedItemCoopRuntime.install(capturedItemCoop);
        } catch (RuntimeException failure) {
            capturedItemCoopInstalled.set(false);
            logger.at(Level.SEVERE).withCause(failure).log(
                    "Captured-item coop intake could not be activated."
            );
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (capturedItemCoopInstalled.compareAndSet(true, false)
                && !CapturedItemCoopRuntime.uninstall(capturedItemCoop)) {
            logger.at(Level.WARNING).log(
                    "Captured-item coop runtime ownership changed "
                            + "before shutdown."
            );
        }
        try {
            liveEvidence.close();
        } finally {
            admissionProviders.close();
        }
    }
}
