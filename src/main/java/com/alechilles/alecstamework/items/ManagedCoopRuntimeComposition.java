package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.systems.ManagedCoopCaptureSourceRetirementSystem;
import com.alechilles.alecstamework.npc.systems.ManagedCoopStaleEntitySuppressionSystem;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopRuntimeServices;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

/**
 * Owns the complete live schema-v5 managed-coop graph and its process-local intake binding.
 *
 * <p>The plugin registers only the three exposed systems. Every lifecycle collaborator shares one
 * trusted composite index epoch, and shutdown clears only this composition's captured-item
 * handler before persistence begins draining.</p>
 */
public final class ManagedCoopRuntimeComposition implements AutoCloseable {
    private final ManagedCoopRuntimeSystem runtimeSystem;
    private final ManagedCoopCaptureSourceRetirementSystem sourceRetirementSystem;
    private final ManagedCoopStaleEntitySuppressionSystem staleEntitySuppressionSystem;
    private final ManagedCoopItemIntakeHandler itemIntakeHandler;
    private final ManagedCoopImportControl importControl;
    private final ManagedCoopAuthorityEligibilityIndex authorityEligibility;
    private final ManagedCoopCrossWorldAliasRetirementCoordinator crossWorldAliasRetirement;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ManagedCoopRuntimeComposition(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull OwnerPopulationRuntime population,
            @Nonnull CoopResidentStateSnapshotService snapshots,
            @Nonnull LoadedNpcIdentityIndex loadedIdentities,
            @Nonnull ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                    projectionIdentityType) {
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(population, "population");
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(loadedIdentities, "loadedIdentities");
        Objects.requireNonNull(projectionIdentityType, "projectionIdentityType");

        importControl = ManagedCoopImportControl.shared();
        importControl.clearAll();
        ManagedCoopRuntimeServices services = persistence.getManagedCoopServices();
        BreedingCaptureCancellationService breedingCancellation =
                new BreedingCaptureCancellationService();
        ManagedCoopCaptureCoordinator captureCoordinator = new ManagedCoopCaptureCoordinator(
                services.captureProfileRepository(),
                services.lifecycleRepository(),
                services.compositeIndexRefreshService(),
                population.coopCaptureAdmissionService(),
                population.identityResolver());
        ManagedCoopCaptureRuntimeAdapter captureAdapter = new ManagedCoopCaptureRuntimeAdapter(
                services.occupancyService(), breedingCancellation, snapshots, captureCoordinator);
        ManagedCoopCaptureSourceRetirementService sourceRetirements =
                new ManagedCoopCaptureSourceRetirementService(
                        services.lifecycleRepository(),
                        services.compositeIndexRefreshService(),
                        services.residentIndex(),
                        services.lifecycleIndex());

        ManagedCoopItemCaptureFinalizer itemFinalizer = new ManagedCoopItemCaptureFinalizer(
                services.lifecycleRepository(), services.compositeIndexRefreshService());
        ManagedCoopItemCaptureRecoveryService itemRecovery =
                new ManagedCoopItemCaptureRecoveryService(itemFinalizer);
        ManagedCoopReleasePresentationDispatcher presentation =
                new ManagedCoopReleasePresentationDispatcher(
                        services.compositeIndexRefreshService(),
                        services.residentIndex(),
                        services.lifecycleIndex());
        ManagedCoopReleasePopulationCoordinator releasePopulations =
                new ManagedCoopReleasePopulationCoordinator(
                        population.coopReleaseAdmissionService(),
                        services.lifecycleRepository());
        ManagedCoopPersistedProjectionRecovery persistedRelease =
                new ManagedCoopPersistedReleaseProjectionRecoveryService(
                        persistence.getCompanionPersistedProjectionEvidenceRegistry(),
                        releasePopulations,
                        services.lifecycleRepository(),
                        services.compositeIndexRefreshService(),
                        population.index(),
                        population.identityResolver(),
                        population.claimOccupancyIndex());
        ManagedCoopReleaseRuntimeAdapter releaseAdapter = releaseAdapter(
                services, loadedIdentities, presentation);
        ManagedCoopLifecycleMutationGate lifecycleGate =
                new ManagedCoopLifecycleMutationGate(
                        () -> managedRuntimeReady(persistence, population));
        ManagedCoopReleaseCoordinator releaseCoordinator = new ManagedCoopReleaseCoordinator(
                services.lifecycleRepository(), services.compositeIndexRefreshService());
        ManagedCoopRuntimeOperationDispatcher operations = operationDispatcher(
                services, captureAdapter, sourceRetirements, releaseCoordinator,
                releaseAdapter, releasePopulations, lifecycleGate);
        ManagedCoopReleaseRecoveryService releaseRecovery =
                new ManagedCoopReleaseRecoveryService(
                        services.lifecycleRepository(),
                        services.residentIndex(),
                        services.lifecycleIndex(),
                        services.compositeIndexRefreshService(),
                        persistedRelease);
        ManagedCoopLifecycleRecoveryService lifecycleRecovery =
                new ManagedCoopLifecycleRecoveryService(
                        services.lifecycleRepository(),
                        services.residentIndex(),
                        services.lifecycleIndex(),
                        services.compositeIndexRefreshService(),
                        sourceRetirements,
                        itemRecovery,
                        releaseRecovery,
                        releaseAdapter,
                        releasePopulations,
                        operations::releaseInFlight,
                        lifecycleGate);
        ManagedCoopVanillaImportBehavior imports = importBehavior(
                persistence,
                services,
                snapshots,
                loadedIdentities,
                projectionIdentityType);

        ManagedCoopLifecycleAdmissionGuard lifecycleAdmission =
                new ManagedCoopLifecycleAdmissionGuard(
                        services.lifecycleIndex(),
                        services.compositeIndexRefreshService()::isTrusted,
                        () -> managedRuntimeReady(persistence, population));
        ManagedCoopRuntimeSweepPlanner planner = new ManagedCoopRuntimeSweepPlanner(
                services.occupancyService(), lifecycleAdmission);
        authorityEligibility = new ManagedCoopAuthorityEligibilityIndex();
        ManagedCoopStaleEntityPolicy stalePolicy = new ManagedCoopStaleEntityPolicy(
                services.residentIndex(),
                services.lifecycleIndex(),
                authorityEligibility,
                services.compositeIndexRefreshService()::isTrusted);
        crossWorldAliasRetirement = new ManagedCoopCrossWorldAliasRetirementCoordinator(
                loadedIdentities,
                stalePolicy::decide,
                new HytaleManagedCoopCrossWorldAliasRuntimeGateway(
                        NPCEntity.getComponentType(),
                        UUIDComponent.getComponentType(),
                        projectionIdentityType),
                ManagedCoopCrossWorldAliasRetirementCoordinator.RetirementObserver.noop());
        staleEntitySuppressionSystem = new ManagedCoopStaleEntitySuppressionSystem(
                stalePolicy,
                NPCEntity.getComponentType(),
                UUIDComponent.getComponentType(),
                projectionIdentityType,
                ManagedCoopStaleEntitySuppressionSystem.DecisionSink.noop(),
                crossWorldAliasRetirement);
        ManagedCoopAncillaryBehavior ancillary = new ManagedCoopAncillaryBehavior(
                services.residentIndex(),
                services.lifecycleIndex(),
                services.compositeIndexRefreshService());
        ManagedCoopRemovedCoopReconciler removedCoops =
                new ManagedCoopRemovedCoopReconciler(
                        services.residentRepository(),
                        services.residentIndex(),
                        services.lifecycleIndex(),
                        services.compositeIndexRefreshService(),
                        operations);
        ManagedCoopRuntimeSweepOrchestrator orchestrator =
                new ManagedCoopRuntimeSweepOrchestrator(
                        new ManagedCoopChunkScanner(authorityEligibility),
                        new ManagedCoopRuntimeCandidateScanner(stalePolicy),
                        staleEntitySuppressionSystem::reevaluate,
                        planner,
                        operations,
                        importGate(imports),
                        lifecycleRecovery::recover,
                        ancillary,
                        removedCoops);

        runtimeSystem = new ManagedCoopRuntimeSystem(orchestrator);
        sourceRetirementSystem = new ManagedCoopCaptureSourceRetirementSystem(
                sourceRetirements,
                UUIDComponent.getComponentType(),
                projectionIdentityType);
        itemIntakeHandler = new ManagedCoopItemIntakeHandler(
                services.occupancyService(),
                persistence.getNpcProfileRepository(),
                captureCoordinator,
                itemFinalizer);
        ManagedCoopCapturedItemAuthoringService itemAuthoring =
                new ManagedCoopCapturedItemAuthoringService(
                        persistence.getNpcProfileRepository(), snapshots, breedingCancellation);
        ManagedCoopItemIntakeRuntime.install(itemIntakeHandler, itemAuthoring);
    }

    @Nonnull
    public ManagedCoopRuntimeSystem runtimeSystem() {
        return runtimeSystem;
    }

    @Nonnull
    public ManagedCoopCaptureSourceRetirementSystem sourceRetirementSystem() {
        return sourceRetirementSystem;
    }

    /** Suppresses housed sources and historical aliases immediately when they enter a store. */
    @Nonnull
    public ManagedCoopStaleEntitySuppressionSystem staleEntitySuppressionSystem() {
        return staleEntitySuppressionSystem;
    }

    /** Revokes one unloaded world's authority proof before any future entity-add callback. */
    public void invalidateManagedAuthorityWorld(@Nonnull String worldName) {
        authorityEligibility.invalidateWorld(worldName);
        crossWorldAliasRetirement.invalidateWorld(worldName);
    }

    /** Revokes all authority proof after managed-coop config assets change. */
    public void invalidateManagedAuthorityEvidence() {
        authorityEligibility.invalidateAll();
        crossWorldAliasRetirement.invalidateAll();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            ManagedCoopItemIntakeRuntime.clear(itemIntakeHandler);
            importControl.clearAll();
            authorityEligibility.close();
            crossWorldAliasRetirement.close();
        }
    }

    @Nonnull
    private ManagedCoopReleaseRuntimeAdapter releaseAdapter(
            ManagedCoopRuntimeServices services,
            LoadedNpcIdentityIndex loadedIdentities,
            ManagedCoopReleasePresentationDispatcher presentation) {
        ManagedCoopReleaseLiveIdentityGuard liveIdentity =
                new ManagedCoopReleaseLiveIdentityGuard(
                        loadedIdentities,
                        services.residentIndex(),
                        services.compositeIndexRefreshService()::isTrusted);
        return new ManagedCoopReleaseRuntimeAdapter(
                liveIdentity,
                ManagedCoopRuntimeComposition::isOwningThread,
                presentation);
    }

    @Nonnull
    private ManagedCoopRuntimeOperationDispatcher operationDispatcher(
            ManagedCoopRuntimeServices services,
            ManagedCoopCaptureRuntimeAdapter captureAdapter,
            ManagedCoopCaptureSourceRetirementService sourceRetirements,
            ManagedCoopReleaseCoordinator releaseCoordinator,
            ManagedCoopReleaseRuntimeAdapter releaseAdapter,
            ManagedCoopReleasePopulationCoordinator releasePopulations,
            ManagedCoopLifecycleMutationGate lifecycleGate) {
        return new ManagedCoopRuntimeOperationDispatcher(
                captureAdapter,
                sourceRetirements,
                releaseCoordinator,
                releaseAdapter,
                services.residentIndex(),
                services.compositeIndexRefreshService(),
                releasePopulations,
                lifecycleGate);
    }

    private static boolean managedRuntimeReady(
            TameworkPersistenceRuntime persistence,
            OwnerPopulationRuntime population) {
        return population.index().readiness() == OwnerPopulationReadiness.READY
                && population.claimOccupancyIndex().readiness()
                == ClaimOccupancyReadiness.READY
                && persistence.getCompanionPersistedProjectionEvidenceRegistry()
                .snapshot().sealed();
    }

    @Nonnull
    private ManagedCoopVanillaImportBehavior importBehavior(
            TameworkPersistenceRuntime persistence,
            ManagedCoopRuntimeServices services,
            CoopResidentStateSnapshotService snapshots,
            LoadedNpcIdentityIndex loadedIdentities,
            ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                    projectionIdentityType) {
        ManagedCoopVanillaImportService importService = new ManagedCoopVanillaImportService(
                services.residentRepository(),
                services.lifecycleRepository(),
                services.importRepository(),
                persistence.getNpcProfileRepository(),
                services.compositeIndexRefreshService(),
                importControl,
                snapshots,
                projectionIdentityType,
                persistence.getNpcIdentityRepository(),
                loadedIdentities);
        return new ManagedCoopVanillaImportBehavior(importService);
    }

    @Nonnull
    private ManagedCoopRuntimeSweepOrchestrator.ImportBehavior importGate(
            ManagedCoopVanillaImportBehavior imports) {
        return (chunkStore, world, context, nowMs) -> {
            ManagedCoopVanillaImportService.SweepResult result =
                    imports.sweep(chunkStore, world, context, nowMs);
            return new ManagedCoopRuntimeSweepOrchestrator.ImportDecision(
                    result.blocksManagedRuntime(), result.detail());
        };
    }

    private static boolean isOwningThread(Store<EntityStore> store) {
        try {
            store.assertThread();
            return true;
        } catch (RuntimeException | AssertionError ignored) {
            return false;
        }
    }
}
