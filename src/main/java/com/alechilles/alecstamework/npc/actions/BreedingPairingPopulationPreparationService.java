package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry;
import com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator;
import com.alechilles.alecstamework.npc.breeding.BreedingPreparedPopulationRegistry;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.npc.breeding.TameworkBreedingServices;
import com.alechilles.alecstamework.ownership.BreedingBirthPlanSnapshot;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionRequest;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Owns population preparation, installation, and activation after local job reservation. */
final class BreedingPairingPopulationPreparationService {
    private final BreedingPairingCoordinator coordinator;
    private final TameworkBreedingServices services;
    private final BreedingParentPreparationService parentPreparation;
    private final BreedingJobPlanSnapshotMapper planSnapshotMapper;
    private final BreedingPreparedPairingHandoffService preparedHandoff;
    private final Consumer<String> warning;

    BreedingPairingPopulationPreparationService(
            @Nonnull BreedingPairingCoordinator coordinator,
            @Nonnull TameworkBreedingServices services,
            @Nonnull BreedingParentPreparationService parentPreparation,
            @Nonnull BreedingJobPlanSnapshotMapper planSnapshotMapper,
            @Nonnull BreedingPreparedPairingHandoffService preparedHandoff,
            @Nonnull Consumer<String> warning) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.services = Objects.requireNonNull(services, "services");
        this.parentPreparation = Objects.requireNonNull(
                parentPreparation, "parentPreparation"
        );
        this.planSnapshotMapper = Objects.requireNonNull(
                planSnapshotMapper, "planSnapshotMapper"
        );
        this.preparedHandoff = Objects.requireNonNull(preparedHandoff, "preparedHandoff");
        this.warning = Objects.requireNonNull(warning, "warning");
    }

    boolean begin(
            @Nonnull BreedingPreparedParents prepared,
            @Nonnull BreedingPairingCoordinator.PairingRequest request,
            @Nonnull BreedingBirthJob job,
            @Nonnull BreedingPairingAttempt attempt,
            @Nonnull BreedingPopulationAdmissionService populationService,
            @Nullable BreedingPopulationSweepContext populationContext,
            @Nullable TwBreedingConfig config,
            @Nonnull Store<EntityStore> store) {
        World world = resolveWorld(store);
        if (world == null) {
            services.jobRegistry().cancel(store, job.jobId());
            return false;
        }
        if (job.plan().isNaturallyEmpty()) {
            dispatchNaturalZero(world, store, request, job);
            return true;
        }
        try {
            preparePopulation(
                    prepared, request, job, attempt, populationService,
                    populationContext, config, store, world
            );
            return true;
        } catch (RuntimeException | LinkageError failure) {
            services.jobRegistry().cancel(store, job.jobId());
            warning.accept("Breeding population handoff setup failed.");
            return false;
        }
    }

    private void preparePopulation(
            BreedingPreparedParents prepared,
            BreedingPairingCoordinator.PairingRequest request,
            BreedingBirthJob job,
            BreedingPairingAttempt attempt,
            BreedingPopulationAdmissionService populationService,
            @Nullable BreedingPopulationSweepContext populationContext,
            @Nullable TwBreedingConfig config,
            Store<EntityStore> store,
            World world) {
        BreedingBirthPlanSnapshot durablePlan = attempt.replay()
                ? attempt.replayState().birthPlan()
                : planSnapshotMapper.snapshot(
                        job.plan(), config, prepared.sourceOwner(), prepared.partnerOwner()
                );
        if (durablePlan == null) {
            throw new IllegalStateException("Replay birth plan is missing");
        }
        List<PlannedChild> outstanding = planSnapshotMapper.outstandingChildren(
                job.plan(), attempt.replayState()
        );
        List<BreedingBirthPlanSnapshot.PlannedChild> outstandingSnapshots =
                planSnapshotMapper.outstandingSnapshots(
                        durablePlan, attempt.replayState()
                );
        List<BreedingBirthPlanSnapshot.PlannedChild> admittedSnapshots =
                planSnapshotMapper.durableChildren(
                        durablePlan,
                        outstanding,
                        outstandingSnapshots,
                        job.initiallyAdmittedChildren()
                );
        if (admittedSnapshots.isEmpty()) {
            throw new IllegalStateException("Breeding shared admission has no durable child");
        }
        BreedingPopulationAdmissionRequest populationRequest =
                BreedingPopulationAdmissionRequestFactory.create(
                        prepared.worldId(),
                        new Vector3d(job.anchor().x(), job.anchor().y(), job.anchor().z()),
                        durablePlan,
                        admittedSnapshots,
                        admittedSnapshots.size(),
                        job.jobId(),
                        prepared.sourceIdentity().profileId(),
                        prepared.partnerIdentity().profileId()
                );
        BreedingPopulationAdmissionService.PreparationContext preparationContext =
                populationContext == null
                        ? null
                        : populationContext.resolve(populationService);
        preparedHandoff.prepareAndDispatch(
                world,
                store,
                job.jobId(),
                populationService,
                populationRequest,
                preparationContext,
                batch -> finalizePreparedPopulation(
                        prepared, request, job, batch, populationService, store
                )
        );
    }

    private void dispatchNaturalZero(
            World world,
            Store<EntityStore> store,
            BreedingPairingCoordinator.PairingRequest request,
            BreedingBirthJob job) {
        LeaseBoundWorldDispatcher.execute(
                world,
                () -> {
                    BreedingPairingCoordinator.PairingResult activated =
                            coordinator.activate(request, job.jobId());
                    if (!activated.accepted()) {
                        services.jobRegistry().cancel(store, job.jobId());
                    }
                },
                () -> services.jobRegistry().cancel(store, job.jobId())
        );
    }

    private boolean finalizePreparedPopulation(
            BreedingPreparedParents prepared,
            BreedingPairingCoordinator.PairingRequest request,
            BreedingBirthJob originalJob,
            PreparedBreedingPopulationBatch batch,
            BreedingPopulationAdmissionService populationService,
            Store<EntityStore> store) {
        if (!parentPreparation.parentsStillCurrent(prepared, store)) {
            return false;
        }
        BreedingBirthJob active = services.jobRegistry()
                .find(store, originalJob.jobId())
                .orElse(null);
        if (active == null || active.state()
                != com.alechilles.alecstamework.npc.breeding.BreedingBirthJobState.RESERVED) {
            return false;
        }
        List<String> childKeys = batch.children().stream()
                .map(PreparedBreedingPopulationBatch.ReservedChild::childKey)
                .toList();
        List<PlannedChild> retained;
        try {
            retained = planSnapshotMapper.coreChildrenForKeys(active.plan(), childKeys);
        } catch (RuntimeException exception) {
            return false;
        }
        BreedingBirthJobRegistry.AdmissionUpdateResult shrunk =
                services.jobRegistry().shrinkAdmission(
                        store, active.jobId(), retained
                );
        if (shrunk.status() != BreedingBirthJobRegistry.AdmissionUpdateStatus.APPLIED
                && shrunk.status() != BreedingBirthJobRegistry.AdmissionUpdateStatus.UNCHANGED) {
            return false;
        }
        BreedingPreparedPopulationRegistry.InstallStatus installed =
                services.preparedPopulationRegistry().install(
                        store, active.jobId(), populationService, batch
                );
        if (installed == BreedingPreparedPopulationRegistry.InstallStatus.CONFLICT) {
            return false;
        }
        BreedingPairingCoordinator.PairingResult activated =
                coordinator.activate(request, active.jobId());
        return activated.accepted();
    }

    @Nullable
    private World resolveWorld(Store<EntityStore> store) {
        return store.getExternalData() == null
                ? null
                : store.getExternalData().getWorld();
    }
}
