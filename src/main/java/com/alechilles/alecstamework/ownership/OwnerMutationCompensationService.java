package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Runs live owner rollback strictly between the durable COMPENSATING and FAILED journal
 * boundaries, resolving the entity again on its world thread after each SQLite completion.
 */
final class OwnerMutationCompensationService {
    private final CompanionPopulationAdmissionCoordinator coordinator;
    private final OwnerComponentMutationService mutationService;
    private final OwnerMutationTerminality terminality;

    OwnerMutationCompensationService(
            @Nonnull CompanionPopulationAdmissionCoordinator coordinator,
            @Nonnull OwnerComponentMutationService mutationService,
            @Nonnull OwnerMutationTerminality terminality
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService");
        this.terminality = Objects.requireNonNull(terminality, "terminality");
    }

    void handleFailedWrite(
            @Nonnull World world,
            @Nonnull UUID npcUuid,
            @Nonnull String profileId,
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull OwnerMutationScheduler.MutationCallbacks callbacks,
            @Nonnull OwnerMutationContext context,
            @Nonnull OwnerComponentMutationService.WriteResult result
    ) {
        if (result.compensationRequired()) {
            compensate(world, npcUuid, profileId, prepared, callbacks, result);
            return;
        }
        notifyCompensated(callbacks, profileId, result.reason(), context);
        if (result.safeToCancel()) {
            terminality.cancel(prepared, result.reason());
            terminality.denied(callbacks, result.reason(), prepared.ownerAdmission().decision());
        } else {
            terminality.degrade("owner_mutation_live_write_ambiguous");
            terminality.durabilityDegraded(callbacks, result.reason());
        }
    }

    void compensate(
            @Nonnull World world,
            @Nonnull UUID npcUuid,
            @Nonnull String profileId,
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull OwnerMutationScheduler.MutationCallbacks callbacks,
            @Nonnull OwnerComponentMutationService.WriteResult result
    ) {
        OwnerComponentMutationService.CompensationPlan plan = result.compensationPlan();
        if (plan == null) {
            reportDurabilityFailure(world, callbacks, "owner-mutation-compensation-plan-missing");
            return;
        }
        final CompletableFuture<Boolean> start;
        try {
            start = coordinator.beginCompensationAsync(prepared, result.reason());
        } catch (RuntimeException | LinkageError failure) {
            reportDurabilityFailure(world, callbacks, "owner-mutation-compensation-start-failed");
            return;
        }
        if (start == null) {
            reportDurabilityFailure(world, callbacks, "owner-mutation-compensation-stage-missing");
            return;
        }
        start.whenComplete((started, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(started)) {
                reportDurabilityFailure(world, callbacks, "owner-mutation-compensation-start-failed");
                return;
            }
            LeaseBoundWorldDispatcher.execute(
                    world,
                    () -> restoreOnWorld(world, npcUuid, profileId, prepared, callbacks, result.reason(), plan),
                    () -> compensationDispatchRejected(callbacks)
            );
        });
    }

    private void restoreOnWorld(
            @Nonnull World world,
            @Nonnull UUID npcUuid,
            @Nonnull String profileId,
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull OwnerMutationScheduler.MutationCallbacks callbacks,
            @Nonnull String reason,
            @Nonnull OwnerComponentMutationService.CompensationPlan plan
    ) {
        Ref<EntityStore> liveRef = world.getEntityRef(npcUuid);
        Store<EntityStore> liveStore = world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        if (liveRef == null || !liveRef.isValid() || liveStore == null) {
            reportDurabilityFailureOnWorld(callbacks, "owner-mutation-compensation-target-unavailable");
            return;
        }
        OwnerMutationContext context = new OwnerMutationContext(liveRef, liveStore, npcUuid);
        boolean derivedRestored;
        try {
            derivedRestored = mutationService.compensateDerivedImmediate(liveRef, liveStore, plan);
        } catch (RuntimeException | LinkageError failure) {
            derivedRestored = false;
        }
        if (!derivedRestored) {
            notifyCompensated(callbacks, profileId, "owner-component-write-ambiguous", context);
            reportDurabilityFailureOnWorld(callbacks, "owner-mutation-compensation-ambiguous");
            return;
        }
        if (!notifyCompensated(callbacks, profileId, reason, context)) {
            reportDurabilityFailureOnWorld(callbacks, "owner-mutation-source-compensation-failed");
            return;
        }
        boolean ownerRestored;
        try {
            ownerRestored = mutationService.compensateOwnerImmediate(liveRef, liveStore, plan);
        } catch (RuntimeException | LinkageError failure) {
            ownerRestored = false;
        }
        if (!ownerRestored) {
            reportDurabilityFailureOnWorld(callbacks, "owner-mutation-compensation-ambiguous");
            return;
        }
        closeCompensation(world, prepared, callbacks, reason);
    }

    private void closeCompensation(
            @Nonnull World world,
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull OwnerMutationScheduler.MutationCallbacks callbacks,
            @Nonnull String reason
    ) {
        final CompletableFuture<Boolean> close;
        try {
            close = coordinator.completeCompensationAsync(prepared, reason);
        } catch (RuntimeException | LinkageError failure) {
            reportDurabilityFailure(world, callbacks, "owner-mutation-compensation-close-failed");
            return;
        }
        if (close == null) {
            reportDurabilityFailure(world, callbacks, "owner-mutation-compensation-close-stage-missing");
            return;
        }
        close.whenComplete((closed, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(closed)) {
                reportDurabilityFailure(world, callbacks, "owner-mutation-compensation-close-failed");
                return;
            }
            LeaseBoundWorldDispatcher.execute(
                    world,
                    () -> terminality.denied(callbacks, reason, prepared.ownerAdmission().decision()),
                    () -> callbacks.onWorldDispatchRejected(
                            "owner-mutation-compensation-complete-world-unavailable", false, null
                    )
            );
        });
    }

    private void reportDurabilityFailure(
            @Nonnull World world,
            @Nonnull OwnerMutationScheduler.MutationCallbacks callbacks,
            @Nonnull String reason
    ) {
        terminality.degrade("owner_mutation_compensation_failed");
        LeaseBoundWorldDispatcher.execute(
                world,
                () -> terminality.durabilityDegraded(callbacks, reason),
                () -> callbacks.onWorldDispatchRejected(reason, true, null)
        );
    }

    private void reportDurabilityFailureOnWorld(
            @Nonnull OwnerMutationScheduler.MutationCallbacks callbacks,
            @Nonnull String reason
    ) {
        terminality.degrade("owner_mutation_compensation_failed");
        terminality.durabilityDegraded(callbacks, reason);
    }

    private void compensationDispatchRejected(
            @Nonnull OwnerMutationScheduler.MutationCallbacks callbacks
    ) {
        terminality.degrade("owner_mutation_compensation_world_unavailable");
        callbacks.onWorldDispatchRejected(
                "owner-mutation-compensation-world-unavailable", true, null
        );
    }

    private static boolean notifyCompensated(
            @Nonnull OwnerMutationScheduler.MutationCallbacks callbacks,
            @Nonnull String profileId,
            @Nonnull String reason,
            @Nonnull OwnerMutationContext context
    ) {
        try {
            callbacks.onApplyCompensated(profileId, reason, context);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
