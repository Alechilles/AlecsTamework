package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Completes managed admission and delayed submission for one frozen litter. */
final class BreedingLitterCommitService {
    private final BreedingLitterPlanner planner;
    private final BreedingParentCooldownResolver cooldowns =
            new BreedingParentCooldownResolver();
    private final BreedingPairEffectsService effects =
            new BreedingPairEffectsService();
    private final BreedingPairingEffectsService delayed =
            new BreedingPairingEffectsService(
                    new BreedingParticleOffsetResolver()
            );

    BreedingLitterCommitService(BreedingLitterPlanner planner) {
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    boolean prepare(
            String worldName,
            BreedingPairContext context,
            BreedingLitterPlanner.Plan plan,
            BreedingPairAdmissionRegistry.Lease lease,
            @Nullable UUID playerUuid
    ) {
        BreedingLitterRuntime runtime = BreedingLitterRuntime.current();
        if (plan.admission() == null) {
            lease.close();
            return false;
        }
        try {
            runtime.prepareManaged(plan.admission())
                    .whenComplete((decision, failure) -> admitted(
                            runtime,
                            worldName,
                            context,
                            plan,
                            lease,
                            decision,
                            failure,
                            playerUuid
                    ));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            lease.close();
            return false;
        }
    }

    boolean applyPairEffects(
            BreedingPairCandidate candidate,
            @Nullable TwBreedingConfig config,
            @Nullable CommandBuffer<EntityStore> commandBuffer
    ) {
        long now = BreedingTimeService.resolveCurrentTimeMs(
                candidate.store()
        );
        BreedingParentCooldownResolver.ResolvedCooldown source =
                cooldowns.resolve(
                        config,
                        candidate.sourceRef(),
                        candidate.store()
                );
        BreedingParentCooldownResolver.ResolvedCooldown partner =
                cooldowns.resolve(
                        config,
                        candidate.partnerRef(),
                        candidate.store()
                );
        return effects.apply(new BreedingPairEffectsService.EffectContext(
                candidate.sourceRef(),
                candidate.sourceNpc(),
                candidate.sourceBreeding(),
                candidate.partnerRef(),
                candidate.partnerNpc(),
                candidate.partnerBreeding(),
                source,
                partner,
                candidate.sourceOwner(),
                candidate.partnerOwner(),
                now,
                System.currentTimeMillis(),
                candidate.store(),
                commandBuffer
        ));
    }

    private void admitted(
            BreedingLitterRuntime runtime,
            String worldName,
            BreedingPairContext context,
            BreedingLitterPlanner.Plan plan,
            BreedingPairAdmissionRegistry.Lease lease,
            PopulationAdmissionDecision decision,
            Throwable failure,
            @Nullable UUID playerUuid
    ) {
        BreedingInteractionOutcome admissionFailure = admissionFailure(
                decision, failure
        );
        if (admissionFailure != null || decision == null
                || decision.token() == null) {
            lease.close();
            notifyPlayer(worldName, playerUuid, admissionFailure);
            return;
        }
        BreedingLitterOperation litter;
        try {
            litter = planner.operation(
                    plan,
                    context,
                    worldName,
                    decision.token(),
                    System.currentTimeMillis()
            );
        } catch (RuntimeException invalid) {
            cancelWithoutJob(runtime, decision, lease);
            return;
        }
        runtime.prepareDurable(litter)
                .whenComplete((prepared, prepareFailure) -> {
                    if (prepareFailure != null
                            || !Boolean.TRUE.equals(prepared)) {
                        cancelWithoutJob(runtime, decision, lease);
                        return;
                    }
                    dispatchPrepared(
                            runtime, worldName, context, litter, lease
                    );
                });
    }

    @Nullable
    static BreedingInteractionOutcome admissionFailure(
            @Nullable PopulationAdmissionDecision decision,
            @Nullable Throwable failure
    ) {
        if (failure != null || decision == null) {
            return BreedingInteractionOutcome.integrationUnavailable();
        }
        if (decision.accepted() && decision.token() != null) {
            return null;
        }
        String reason = decision.reason();
        if ("population_domain_owned_capacity_reached".equals(reason)
                || "population_domain_deployable_capacity_reached".equals(reason)) {
            return BreedingInteractionOutcome.capacityReached();
        }
        if ("runehusbandry.admission.family_locked".equals(reason)) {
            return BreedingInteractionOutcome.progressionRequired();
        }
        return BreedingInteractionOutcome.integrationUnavailable();
    }

    private static void notifyPlayer(
            String worldName,
            @Nullable UUID playerUuid,
            @Nullable BreedingInteractionOutcome outcome
    ) {
        if (playerUuid == null || outcome == null) {
            return;
        }
        World world = Universe.get().getWorld(worldName);
        if (world == null || !world.isAlive()) {
            return;
        }
        try {
            world.execute(() -> {
                World current = Universe.get().getWorld(worldName);
                if (current != world || !world.isAlive()
                        || world.getEntityStore() == null) {
                    return;
                }
                Ref<EntityStore> playerRef = world.getEntityRef(playerUuid);
                if (playerRef == null || !playerRef.isValid()) {
                    return;
                }
                Player player = world.getEntityStore().getStore().getComponent(
                        playerRef, Player.getComponentType()
                );
                if (player == null) {
                    return;
                }
                BreedingInteractionOutcome.Feedback feedback = outcome.feedback();
                new InteractionUiMessageService().showWarningKey(
                        player, feedback.key(), feedback.arguments()
                );
            });
        } catch (RuntimeException | LinkageError ignored) {
            // The world can close between the liveness check and dispatch.
        }
    }

    private void dispatchPrepared(
            BreedingLitterRuntime runtime,
            String worldName,
            BreedingPairContext context,
            BreedingLitterOperation litter,
            BreedingPairAdmissionRegistry.Lease lease
    ) {
        World world = Universe.get().getWorld(worldName);
        if (world == null || !world.isAlive()) {
            cancelAndFinishJob(runtime, litter, lease);
            return;
        }
        world.execute(() -> {
            BreedingPairCandidate live = resolve(world, context);
            TwBreedingConfig config = resolveConfig(
                    context.breedingConfigId()
            );
            if (live == null
                    || context.breedingConfigId() != null && config == null
                    || !sameOwners(live, context)
                    || !applyPairEffects(live, config, null)) {
                cancelAndFinishJob(runtime, litter, lease);
                return;
            }
            schedule(runtime, world, context, litter, lease);
        });
    }

    @Nullable
    private BreedingPairCandidate resolve(
            World world,
            BreedingPairContext context
    ) {
        if (world == null || world.getEntityStore() == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> source = world.getEntityRef(
                context.parentAUuid()
        );
        Ref<EntityStore> partner = world.getEntityRef(
                context.parentBUuid()
        );
        if (source == null || partner == null
                || !source.isValid() || !partner.isValid()) {
            return null;
        }
        NPCEntity sourceNpc = store.getComponent(
                source, NPCEntity.getComponentType()
        );
        NPCEntity partnerNpc = store.getComponent(
                partner, NPCEntity.getComponentType()
        );
        TameworkBreedingComponent sourceBreeding = breeding(source, store);
        TameworkBreedingComponent partnerBreeding = breeding(partner, store);
        if (sourceNpc == null || partnerNpc == null
                || sourceBreeding == null || partnerBreeding == null) {
            return null;
        }
        return new BreedingPairCandidate(
                source,
                partner,
                sourceNpc,
                partnerNpc,
                sourceBreeding,
                partnerBreeding,
                store,
                world,
                context.spawnAnchor(),
                BreedingOwnerSnapshotResolver.resolve(source, store),
                BreedingOwnerSnapshotResolver.resolve(partner, store)
        );
    }

    private boolean schedule(
            BreedingLitterRuntime runtime,
            World world,
            BreedingPairContext context,
            BreedingLitterOperation litter,
            BreedingPairAdmissionRegistry.Lease lease
    ) {
        try {
            delayed.schedule(
                    world,
                    context.parentAUuid(),
                    context.parentBUuid(),
                    () -> submit(runtime, litter, lease),
                    () -> cancelAndFinishJob(runtime, litter, lease)
            );
            return true;
        } catch (RuntimeException | LinkageError failure) {
            cancelAndFinishJob(runtime, litter, lease);
            return false;
        }
    }

    private void cancelWithoutJob(
            BreedingLitterRuntime runtime,
            PopulationAdmissionDecision decision,
            BreedingPairAdmissionRegistry.Lease lease
    ) {
        runtime.cancelManaged(decision.token())
                .whenComplete((ignored, failure) -> lease.close());
    }

    private void cancelAndFinishJob(
            BreedingLitterRuntime runtime,
            BreedingLitterOperation litter,
            BreedingPairAdmissionRegistry.Lease lease
    ) {
        runtime.cancelManaged(litter.admissionToken())
                .whenComplete((ignored, failure) ->
                        submit(runtime, litter, lease));
    }

    private static void submit(
            BreedingLitterRuntime runtime,
            BreedingLitterOperation litter,
            BreedingPairAdmissionRegistry.Lease lease
    ) {
        PublicOperationSubmission submission =
                runtime.submitDurable(litter);
        if (submission == null || !submission.accepted()) {
            lease.close();
            return;
        }
        submission.completion().whenComplete(
                (result, failure) -> lease.close()
        );
    }

    private static boolean sameOwners(
            BreedingPairCandidate live,
            BreedingPairContext frozen
    ) {
        return Objects.equals(
                live.sourceOwner().ownerId(),
                frozen.parentAOwner().ownerId()
        ) && Objects.equals(
                live.partnerOwner().ownerId(),
                frozen.parentBOwner().ownerId()
        );
    }

    @Nullable
    private static TameworkBreedingComponent breeding(
            Ref<EntityStore> ref,
            Store<EntityStore> store
    ) {
        ComponentType<EntityStore, TameworkBreedingComponent> type =
                TameworkBreedingComponent.getComponentType();
        return type == null ? null : store.getComponent(ref, type);
    }

    @Nullable
    private static TwBreedingConfig resolveConfig(String configId) {
        return configId == null || configId.isBlank()
                ? null : TwBreedingConfig.resolveById(configId);
    }
}
