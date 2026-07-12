package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.systems.ManagedCoopCaptureSourceRetirementSystem;
import com.alechilles.alecstamework.npc.systems.ManagedCoopStaleEntitySuppressionSystem;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopRuntimeServices;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
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
 * <p>The plugin registers only the two exposed systems. Every lifecycle collaborator shares one
 * trusted composite index epoch, and shutdown clears only this composition's captured-item
 * handler before persistence begins draining.</p>
 */
public final class ManagedCoopRuntimeComposition implements AutoCloseable {
    private final ManagedCoopRuntimeSystem runtimeSystem;
    private final ManagedCoopCaptureSourceRetirementSystem sourceRetirementSystem;
    private final ManagedCoopStaleEntitySuppressionSystem staleEntitySuppressionSystem;
    private final ManagedCoopItemIntakeHandler itemIntakeHandler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ManagedCoopRuntimeComposition(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull CoopResidentStateSnapshotService snapshots,
            @Nonnull LoadedNpcIdentityIndex loadedIdentities,
            @Nonnull ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                    projectionIdentityType) {
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(loadedIdentities, "loadedIdentities");
        Objects.requireNonNull(projectionIdentityType, "projectionIdentityType");

        ManagedCoopRuntimeServices services = persistence.getManagedCoopServices();
        BreedingCaptureCancellationService breedingCancellation =
                new BreedingCaptureCancellationService();
        ManagedCoopCaptureCoordinator captureCoordinator = new ManagedCoopCaptureCoordinator(
                services.captureProfileRepository(),
                services.lifecycleRepository(),
                services.compositeIndexRefreshService());
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
        ManagedCoopReleaseRuntimeAdapter releaseAdapter = releaseAdapter(
                services, loadedIdentities, presentation);
        ManagedCoopReleaseRecoveryService releaseRecovery =
                new ManagedCoopReleaseRecoveryService(
                        services.lifecycleRepository(),
                        services.residentIndex(),
                        services.lifecycleIndex(),
                        services.compositeIndexRefreshService());
        ManagedCoopLifecycleRecoveryService lifecycleRecovery =
                new ManagedCoopLifecycleRecoveryService(
                        services.lifecycleRepository(),
                        services.residentIndex(),
                        services.lifecycleIndex(),
                        services.compositeIndexRefreshService(),
                        sourceRetirements,
                        itemRecovery,
                        releaseRecovery,
                        releaseAdapter);
        ManagedCoopRuntimeOperationDispatcher operations = operationDispatcher(
                services, captureAdapter, sourceRetirements, releaseAdapter);
        ManagedCoopVanillaImportBehavior imports = importBehavior(persistence, services);

        ManagedCoopLifecycleAdmissionGuard lifecycleAdmission =
                new ManagedCoopLifecycleAdmissionGuard(
                        services.lifecycleIndex(),
                        services.compositeIndexRefreshService()::isTrusted);
        ManagedCoopRuntimeSweepPlanner planner = new ManagedCoopRuntimeSweepPlanner(
                services.occupancyService(), lifecycleAdmission);
        ManagedCoopStaleEntityPolicy stalePolicy = new ManagedCoopStaleEntityPolicy(
                services.residentIndex(),
                services.lifecycleIndex(),
                services.compositeIndexRefreshService()::isTrusted);
        staleEntitySuppressionSystem = new ManagedCoopStaleEntitySuppressionSystem(
                stalePolicy,
                NPCEntity.getComponentType(),
                UUIDComponent.getComponentType(),
                projectionIdentityType);
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
                        new ManagedCoopChunkScanner(),
                        new ManagedCoopRuntimeCandidateScanner(stalePolicy),
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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            ManagedCoopItemIntakeRuntime.clear(itemIntakeHandler);
        }
    }

    @Nonnull
    private ManagedCoopReleaseRuntimeAdapter releaseAdapter(
            ManagedCoopRuntimeServices services,
            LoadedNpcIdentityIndex loadedIdentities,
            ManagedCoopReleasePresentationDispatcher presentation) {
        ManagedCoopReleaseProjectionCoordinator projectionCoordinator =
                new ManagedCoopReleaseProjectionCoordinator(
                        services.lifecycleRepository(),
                        services.compositeIndexRefreshService());
        ManagedCoopReleaseLiveIdentityGuard liveIdentity =
                new ManagedCoopReleaseLiveIdentityGuard(
                        loadedIdentities,
                        services.residentIndex(),
                        services.compositeIndexRefreshService()::isTrusted);
        return new ManagedCoopReleaseRuntimeAdapter(
                projectionCoordinator,
                liveIdentity,
                ManagedCoopRuntimeComposition::isOwningThread,
                presentation);
    }

    @Nonnull
    private ManagedCoopRuntimeOperationDispatcher operationDispatcher(
            ManagedCoopRuntimeServices services,
            ManagedCoopCaptureRuntimeAdapter captureAdapter,
            ManagedCoopCaptureSourceRetirementService sourceRetirements,
            ManagedCoopReleaseRuntimeAdapter releaseAdapter) {
        ManagedCoopReleaseCoordinator releaseCoordinator = new ManagedCoopReleaseCoordinator(
                services.lifecycleRepository(), services.compositeIndexRefreshService());
        return new ManagedCoopRuntimeOperationDispatcher(
                captureAdapter,
                sourceRetirements,
                releaseCoordinator,
                releaseAdapter,
                services.residentIndex(),
                services.compositeIndexRefreshService());
    }

    @Nonnull
    private ManagedCoopVanillaImportBehavior importBehavior(
            TameworkPersistenceRuntime persistence,
            ManagedCoopRuntimeServices services) {
        ManagedCoopVanillaImportService importService = new ManagedCoopVanillaImportService(
                services.residentRepository(),
                services.lifecycleRepository(),
                services.importRepository(),
                persistence.getNpcProfileRepository(),
                services.compositeIndexRefreshService());
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
