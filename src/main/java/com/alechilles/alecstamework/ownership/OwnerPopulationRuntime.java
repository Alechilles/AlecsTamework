package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesClaimsProviderProbe;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsProviderProbe;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionLiveEvidenceRevision;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationReconciliationProgress;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationReconciliationRuntime;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationStartupReconciler;
import com.alechilles.alecstamework.ownership.reconciliation.ReconciliationEvidenceRecoveryProofRegistry;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationRuntimeReconciler;
import com.alechilles.alecstamework.ownership.reconciliation.CustomContainerReconciliationRegistry;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupEventSink;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.health.BreedingPersistenceMutationGate;
import com.alechilles.alecstamework.npc.breeding.TameworkBreedingServices;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Groups the owner population index, identity cache, durable coordinator, and bootstrap result.
 */
public final class OwnerPopulationRuntime implements AutoCloseable {
    private final OwnerPopulationIndex index;
    private final CompanionIdentityResolver identityResolver;
    private final OwnerPopulationAdmissionCoordinator admissionCoordinator;
    private final OwnerComponentMutationService mutationService;
    private final OwnerMutationScheduler mutationScheduler;
    private final ClaimOccupancyIndex claimOccupancyIndex;
    private final ClaimAdmissionService claimAdmissionService;
    private final ClaimProviderRegistry claimProviderRegistry;
    private final ClaimLookupMetrics claimLookupMetrics;
    private final CompanionPopulationAdmissionCoordinator companionAdmissionCoordinator;
    private final CompanionPopulationBatchAdmissionCoordinator companionBatchAdmissionCoordinator;
    private final CompanionSpawnPopulationAdmissionService companionSpawnAdmissionService;
    private final BreedingPopulationAdmissionService breedingAdmissionService;
    private final CoopPopulationCaptureAdmissionService coopCaptureAdmissionService;
    private final CoopPopulationReleaseAdmissionService coopReleaseAdmissionService;
    private final RuntimePopulationPolicyAuthority populationPolicyAuthority;
    private final PublicPopulationCapabilityMaintenance capabilityMaintenance;
    private final CompanionRelocationAdmissionService relocationAdmissionService;
    private final CompanionPopulationReconciliationRuntime reconciliationRuntime;
    private final BreedingReplayJournalLoader breedingReplayJournal;
    private final OwnerPopulationCanonicalRecoveryService canonicalRecoveryService;
    private final CompanionPopulationBootstrapService.BootstrapResult bootstrapResult;
    private volatile PopulationGroupOwnerAdmissionExtension populationGroups;
    private volatile boolean populationGroupsReady;

    private OwnerPopulationRuntime(
            @Nonnull OwnerPopulationIndex index,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull OwnerPopulationAdmissionCoordinator admissionCoordinator,
            @Nonnull OwnerComponentMutationService mutationService,
            @Nonnull OwnerMutationScheduler mutationScheduler,
            @Nonnull ClaimOccupancyIndex claimOccupancyIndex,
            @Nonnull ClaimAdmissionService claimAdmissionService,
            @Nonnull ClaimProviderRegistry claimProviderRegistry,
            @Nonnull ClaimLookupMetrics claimLookupMetrics,
            @Nonnull CompanionPopulationAdmissionCoordinator companionAdmissionCoordinator,
            @Nonnull CompanionPopulationBatchAdmissionCoordinator companionBatchAdmissionCoordinator,
            @Nonnull CompanionSpawnPopulationAdmissionService companionSpawnAdmissionService,
            @Nonnull BreedingPopulationAdmissionService breedingAdmissionService,
            @Nonnull CoopPopulationCaptureAdmissionService coopCaptureAdmissionService,
            @Nonnull CoopPopulationReleaseAdmissionService coopReleaseAdmissionService,
            @Nonnull RuntimePopulationPolicyAuthority populationPolicyAuthority,
            @Nonnull PublicPopulationCapabilityMaintenance capabilityMaintenance,
            @Nonnull CompanionRelocationAdmissionService relocationAdmissionService,
            @Nonnull CompanionPopulationReconciliationRuntime reconciliationRuntime,
            @Nonnull BreedingReplayJournalLoader breedingReplayJournal,
            @Nonnull OwnerPopulationCanonicalRecoveryService canonicalRecoveryService,
            @Nonnull CompanionPopulationBootstrapService.BootstrapResult bootstrapResult
    ) {
        this.index = index;
        this.identityResolver = identityResolver;
        this.admissionCoordinator = admissionCoordinator;
        this.mutationService = mutationService;
        this.mutationScheduler = mutationScheduler;
        this.claimOccupancyIndex = claimOccupancyIndex;
        this.claimAdmissionService = claimAdmissionService;
        this.claimProviderRegistry = claimProviderRegistry;
        this.claimLookupMetrics = claimLookupMetrics;
        this.companionAdmissionCoordinator = companionAdmissionCoordinator;
        this.companionBatchAdmissionCoordinator = companionBatchAdmissionCoordinator;
        this.companionSpawnAdmissionService = companionSpawnAdmissionService;
        this.breedingAdmissionService = breedingAdmissionService;
        this.coopCaptureAdmissionService = coopCaptureAdmissionService;
        this.coopReleaseAdmissionService = coopReleaseAdmissionService;
        this.populationPolicyAuthority = populationPolicyAuthority;
        this.capabilityMaintenance = capabilityMaintenance;
        this.relocationAdmissionService = relocationAdmissionService;
        this.reconciliationRuntime = reconciliationRuntime;
        this.breedingReplayJournal = breedingReplayJournal;
        this.canonicalRecoveryService = canonicalRecoveryService;
        this.bootstrapResult = bootstrapResult;
    }

    @Nonnull
    public static OwnerPopulationRuntime initialize(@Nonnull TameworkPersistenceRuntime persistence) {
        Objects.requireNonNull(persistence, "persistence");
        OwnerPopulationIndex index = new OwnerPopulationIndex();
        CompanionIdentityResolver identityResolver = new CompanionIdentityResolver();
        ClaimOccupancyIndex claimOccupancyIndex = new ClaimOccupancyIndex();
        CompanionPopulationBootstrapService bootstrapService = new CompanionPopulationBootstrapService(
                persistence.getCompanionPopulationRepository(),
                persistence.getCompanionPopulationCoverageRepository(),
                persistence.getCompanionIdentityRepository(),
                persistence.getHealthService(),
                index,
                identityResolver,
                claimOccupancyIndex,
                persistence.getQuarantineRegistry()
        );
        CompanionPopulationBootstrapService.BootstrapResult bootstrap =
                bootstrapService.loadForReconciliation();
        OwnerPopulationCanonicalRecoveryService canonicalRecoveryService =
                new OwnerPopulationCanonicalRecoveryService(
                        bootstrapService,
                        persistence.getCompanionPopulationRepository(),
                        persistence.getCompanionPopulationCoverageRepository(),
                        persistence.getCompanionIdentityRepository());
        boolean canonicalReady = bootstrap.globalReadiness() != OwnerPopulationReadiness.DEGRADED;
        long coverageGeneration = System.currentTimeMillis();
        persistence.getPersistenceCoverageRegistry().publish(
                PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG,
                canonicalReady, bootstrap.reason(), coverageGeneration);
        persistence.getPersistenceCoverageRegistry().publish(
                PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG,
                canonicalReady, bootstrap.reason(), coverageGeneration);
        persistence.getNpcProfileRepository().setIdentityLifecycle(
                new CompanionProfileIdentityLifecycle(identityResolver)
        );
        OwnerPopulationAdmissionCoordinator coordinator = new OwnerPopulationAdmissionCoordinator(
                index,
                persistence.getCompanionPopulationRepository(),
                persistence.getHealthService(),
                persistence.getMutationAvailabilityService(),
                persistence.getIncidentReporter(),
                persistence.getPersistenceScopeFactory()
        );
        ClaimAdmissionService claimAdmissionService = new ClaimAdmissionService(claimOccupancyIndex);
        ClaimProviderRegistry claimProviderRegistry = new ClaimProviderRegistry(
                new QuestLinesClaimsProviderProbe(),
                new SimpleClaimsProviderProbe()
        );
        ClaimLookupMetrics claimLookupMetrics = new ClaimLookupMetrics();
        OwnerComponentMutationService mutationService = new OwnerComponentMutationService(coordinator);
        CompanionPopulationAdmissionCoordinator companionCoordinator =
                new CompanionPopulationAdmissionCoordinator(coordinator, claimAdmissionService);
        CompanionPopulationBatchAdmissionCoordinator companionBatchCoordinator =
                new CompanionPopulationBatchAdmissionCoordinator(companionCoordinator);
        CompanionSpawnPopulationAdmissionService companionSpawnAdmissionService =
                new CompanionSpawnPopulationAdmissionService(
                        index,
                        identityResolver,
                        claimOccupancyIndex,
                        claimProviderRegistry,
                        companionCoordinator,
                        companionBatchCoordinator,
                        mutationService,
                        claimLookupMetrics
                );
        BreedingReplayJournalLoader breedingReplayJournal = new BreedingReplayJournalLoader(
                persistence.getCompanionPopulationRepository(),
                persistence.getHealthService(),
                persistence.getCompanionPersistedProjectionEvidenceRegistry(),
                persistence.getPersistenceCoverageRegistry(),
                persistence.getIncidentReporter(),
                persistence.getPersistenceScopeFactory()
        );
        breedingReplayJournal.refresh();
        BreedingPersistenceMutationGate breedingPersistenceGate =
                new BreedingPersistenceMutationGate(
                        persistence.getMutationAvailabilityService(),
                        persistence.getPersistenceScopeFactory());
        TameworkBreedingServices.shared().installPairingPersistenceGate(
                (parentA, parentB, attemptId, worldId) -> breedingPersistenceGate.decide(
                        parentA, parentB, attemptId, worldId).allowed());
        BreedingPopulationAdmissionService breedingAdmissionService =
                new BreedingPopulationAdmissionService(
                        companionBatchCoordinator,
                        index,
                        claimOccupancyIndex,
                        claimProviderRegistry,
                        mutationService,
                        identityResolver,
                        claimLookupMetrics,
                        breedingReplayJournal.replayService()
                );
        CoopPopulationCaptureAdmissionService coopCaptureAdmissionService =
                new CoopPopulationCaptureAdmissionService(
                        index,
                        identityResolver,
                        claimOccupancyIndex,
                        claimProviderRegistry,
                        companionCoordinator,
                        claimLookupMetrics
                );
        CoopPopulationReleaseAdmissionService coopReleaseAdmissionService =
                new CoopPopulationReleaseAdmissionService(
                        index,
                        identityResolver,
                        claimOccupancyIndex,
                        claimProviderRegistry,
                        companionCoordinator,
                        mutationService,
                        claimLookupMetrics
                );
        OwnerMutationScheduler mutationScheduler = new OwnerMutationScheduler(
                index,
                identityResolver,
                coordinator,
                mutationService,
                companionCoordinator,
                claimOccupancyIndex,
                claimProviderRegistry,
                claimLookupMetrics
        );
        CompanionPopulationReconciliationRuntime reconciliationRuntime =
                new CompanionPopulationReconciliationRuntime(
                        persistence,
                        bootstrapService,
                        index,
                        identityResolver,
                        claimOccupancyIndex
                );
        RuntimePopulationPolicyAuthority populationPolicyAuthority = new RuntimePopulationPolicyAuthority(
                index,
                identityResolver,
                companionCoordinator,
                companionBatchCoordinator,
                claimOccupancyIndex,
                claimAdmissionService,
                claimProviderRegistry,
                System::nanoTime,
                claimLookupMetrics
        );
        PublicPopulationCapabilityMaintenance capabilityMaintenance =
                PublicPopulationCapabilityMaintenance.start(populationPolicyAuthority);
        CompanionRelocationAdmissionService relocationAdmissionService =
                new CompanionRelocationAdmissionService(
                        index, identityResolver, claimOccupancyIndex, populationPolicyAuthority
                );
        populationPolicyAuthority.setReconciliationDiagnostics(() ->
                PopulationReconciliationDiagnosticsMapper.map(reconciliationRuntime.progress())
        );
        return new OwnerPopulationRuntime(
                index,
                identityResolver,
                coordinator,
                mutationService,
                mutationScheduler,
                claimOccupancyIndex,
                claimAdmissionService,
                claimProviderRegistry,
                claimLookupMetrics,
                companionCoordinator,
                companionBatchCoordinator,
                companionSpawnAdmissionService,
                breedingAdmissionService,
                coopCaptureAdmissionService,
                coopReleaseAdmissionService,
                populationPolicyAuthority,
                capabilityMaintenance,
                relocationAdmissionService,
                reconciliationRuntime,
                breedingReplayJournal,
                canonicalRecoveryService,
                bootstrap
        );
    }

    @Nonnull
    public OwnerPopulationIndex index() {
        return index;
    }

    @Nonnull
    public CompanionIdentityResolver identityResolver() {
        return identityResolver;
    }

    @Nonnull
    public OwnerPopulationAdmissionCoordinator admissionCoordinator() {
        return admissionCoordinator;
    }

    /**
     * Recovers durable group operations and installs group admission on the canonical owner
     * coordinator. Callers may publish POPULATION_GROUPS only when the returned report is ready.
     */
    @Nonnull
    public synchronized CompletableFuture<PopulationGroupOwnerAdmissionExtension.RecoveryReport>
    installPopulationGroups(
            @Nonnull PopulationGroupRegistry registry,
            @Nonnull PopulationGroupRepository repository,
            @Nonnull NpcProfileRepository profiles) {
        return installPopulationGroups(
                registry, repository, profiles, PopulationGroupEventSink.noop());
    }

    @Nonnull
    public synchronized CompletableFuture<PopulationGroupOwnerAdmissionExtension.RecoveryReport>
    installPopulationGroups(
            @Nonnull PopulationGroupRegistry registry,
            @Nonnull PopulationGroupRepository repository,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull PopulationGroupEventSink events) {
        PopulationGroupOwnerAdmissionExtension extension = populationGroups;
        if (extension == null) {
            extension = new PopulationGroupOwnerAdmissionExtension(
                    admissionCoordinator, registry, repository, profiles, events);
            populationGroups = extension;
        }
        final PopulationGroupOwnerAdmissionExtension installed = extension;
        populationGroupsReady = false;
        return extension.recover(true).thenApply(report -> {
            if (report.ready()) {
                admissionCoordinator.installPopulationGroups(installed);
                populationGroupsReady = true;
            }
            return report;
        });
    }

    /** Reconciles classifications after a valid population-group config index swap. */
    @Nonnull
    public synchronized CompletableFuture<PopulationGroupOwnerAdmissionExtension.RecoveryReport>
    reconcilePopulationGroups() {
        PopulationGroupOwnerAdmissionExtension extension = populationGroups;
        if (extension == null) {
            return CompletableFuture.completedFuture(
                    new PopulationGroupOwnerAdmissionExtension.RecoveryReport(0, 0, 1, false));
        }
        populationGroupsReady = false;
        return extension.recover(false).thenApply(report -> {
            populationGroupsReady = report.ready();
            return report;
        });
    }

    public boolean populationGroupsReady() {
        return populationGroupsReady;
    }

    /** Emits immutable limit events after a reconciled index has become authoritative. */
    public void publishPopulationGroupLimitChanges(
            @Nonnull PopulationGroupIndex previous,
            @Nonnull PopulationGroupIndex current,
            boolean recovered) {
        PopulationGroupOwnerAdmissionExtension extension = populationGroups;
        if (extension == null || !populationGroupsReady) return;
        extension.publishLimitChanges(previous, current, recovered);
    }

    @Nonnull
    public OwnerComponentMutationService mutationService() {
        return mutationService;
    }

    @Nonnull
    public OwnerMutationScheduler mutationScheduler() {
        return mutationScheduler;
    }

    @Nonnull
    public ClaimOccupancyIndex claimOccupancyIndex() {
        return claimOccupancyIndex;
    }

    @Nonnull
    public ClaimAdmissionService claimAdmissionService() {
        return claimAdmissionService;
    }

    @Nonnull
    public ClaimProviderRegistry claimProviderRegistry() {
        return claimProviderRegistry;
    }

    @Nonnull
    ClaimLookupMetrics claimLookupMetrics() {
        return claimLookupMetrics;
    }

    @Nonnull
    public CompanionPopulationAdmissionCoordinator companionAdmissionCoordinator() {
        return companionAdmissionCoordinator;
    }

    @Nonnull
    public CompanionPopulationBatchAdmissionCoordinator companionBatchAdmissionCoordinator() {
        return companionBatchAdmissionCoordinator;
    }

    @Nonnull
    public CompanionSpawnPopulationAdmissionService companionSpawnAdmissionService() {
        return companionSpawnAdmissionService;
    }

    @Nonnull
    public BreedingPopulationAdmissionService breedingAdmissionService() {
        return breedingAdmissionService;
    }

    @Nonnull
    public CoopPopulationCaptureAdmissionService coopCaptureAdmissionService() {
        return coopCaptureAdmissionService;
    }

    @Nonnull
    public CoopPopulationReleaseAdmissionService coopReleaseAdmissionService() {
        return coopReleaseAdmissionService;
    }

    @Nonnull
    public RuntimePopulationPolicyAuthority populationPolicyAuthority() {
        return populationPolicyAuthority;
    }

    @Nonnull
    public CompanionRelocationAdmissionService relocationAdmissionService() {
        return relocationAdmissionService;
    }

    @Nonnull
    public CompanionPopulationRuntimeReconciler runtimeReconciler() {
        return reconciliationRuntime.runtimeReconciler();
    }

    /** Returns the single loaded-NPC identity index shared with startup reconciliation. */
    @Nonnull
    public LoadedNpcIdentityIndex loadedNpcIdentityIndex() {
        return reconciliationRuntime.loadedNpcIdentityIndex();
    }

    /** Shared online inventory/NPC mutation fence for startup reconciliation. */
    @Nonnull
    public CompanionLiveEvidenceRevision liveEvidenceRevision() {
        return reconciliationRuntime.liveEvidenceRevision();
    }

    @Nonnull
    public CustomContainerReconciliationRegistry customContainerReconciliationRegistry() {
        return reconciliationRuntime.customContainers();
    }

    /** Installs a feature observer at the complete post-seal inventory evidence boundary. */
    public void installSealedProjectionObserver(
            @Nonnull CompanionPopulationStartupReconciler.SealedProjectionObserver observer) {
        reconciliationRuntime.installSealedProjectionObserver(observer);
    }

    @Nonnull
    public CompletableFuture<CompanionPopulationReconciliationProgress> startReconciliation(
            @Nonnull Universe universe,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull Supplier<CompletableFuture<LoadedNpcIdentitySnapshot>> loadedIdentitiesReady
    ) {
        // Keep the successfully loaded journal available for fresh-attempt collision checks.
        // The projection registry itself revokes only persisted restart replay while rescanning.
        return reconciliationRuntime.start(
                        universe, ownerType, itemFeatures, loadedIdentitiesReady
                )
                .thenCompose(progress -> {
                    if (progress.status()
                            != CompanionPopulationReconciliationProgress.Status.READY) {
                        return CompletableFuture.completedFuture(progress);
                    }
                    return breedingReplayJournal.refreshAsync().thenApply(ignored -> progress);
                });
    }

    @Nonnull
    public CompanionPopulationReconciliationProgress reconciliationProgress() {
        return reconciliationRuntime.progress();
    }

    /** Re-runs canonical population reconciliation after an exact retained-source repair. */
    @Nonnull
    public CompletableFuture<CompanionPopulationReconciliationProgress>
    restartReconciliationAfterExternalRepair() {
        return reconciliationRuntime.restartAfterExternalRepair();
    }

    @Nonnull
    public CompanionPopulationBootstrapService.BootstrapResult bootstrapResult() {
        return bootstrapResult;
    }

    @Nonnull
    public OwnerPopulationCanonicalRecoveryService canonicalRecoveryService() {
        return canonicalRecoveryService;
    }

    @Nonnull
    public ReconciliationEvidenceRecoveryProofRegistry reconciliationEvidenceRecoveryProofs() {
        return reconciliationRuntime.recoveryProofs();
    }

    @Override
    public void close() {
        capabilityMaintenance.close();
        reconciliationRuntime.close();
        claimProviderRegistry.close();
    }
}
