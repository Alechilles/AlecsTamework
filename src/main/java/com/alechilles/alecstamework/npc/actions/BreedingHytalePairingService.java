package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Matches a live pair and schedules an ordinary or managed litter.
 */
final class BreedingHytalePairingService {
    private static final double SPAWN_HEIGHT_OFFSET = 1.0;

    private final BreedingPartnerService partnerService;
    private final BreedingPairAdmissionRegistry admissionRegistry;
    private final BreedingLitterPlanner litterPlanner =
            new BreedingLitterPlanner();
    private final BreedingLitterCommitService litterCommit =
            new BreedingLitterCommitService(litterPlanner);
    private final BreedingClaimLimitPolicyService limitPolicy;
    private final BreedingOffspringBirthService ordinaryBirth;
    private final BreedingPairingEffectsService delayedEffects =
            new BreedingPairingEffectsService(new BreedingParticleOffsetResolver());

    BreedingHytalePairingService(
            @Nonnull BreedingPartnerService partnerService,
            @Nonnull BreedingPairAdmissionRegistry admissionRegistry,
            @Nonnull BreedingClaimLimitPolicyService limitPolicy
    ) {
        this.partnerService = partnerService;
        this.admissionRegistry = admissionRegistry;
        this.limitPolicy = limitPolicy;
        this.ordinaryBirth = new BreedingOffspringBirthService(limitPolicy);
    }

    boolean tryPassive(
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> store,
            @Nullable TameworkBreedingComponent sourceBreeding,
            @Nullable TwBreedingConfig config,
            @Nonnull Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer>
                    pendingClaims,
            @Nonnull Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer>
                    pendingOwners,
            @Nullable CommandBuffer<EntityStore> commandBuffer
    ) {
        long now = store == null ? 0L : BreedingTimeService.resolveCurrentTimeMs(store);
        return tryPair(
                sourceRef,
                store,
                sourceBreeding,
                config,
                commandBuffer,
                BreedingReadinessPolicy.passive(now),
                pendingClaims,
                pendingOwners
        ).completedPair();
    }

    BreedingInteractionOutcome tryManual(
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> store,
            @Nullable TameworkBreedingComponent sourceBreeding,
            @Nullable TwBreedingConfig config,
            @Nonnull UUID playerUuid
    ) {
        return tryPair(
                sourceRef,
                store,
                sourceBreeding,
                config,
                null,
                BreedingReadinessPolicy.manual(playerUuid, ManualBreedingClock.nowMs()),
                null,
                null
        );
    }

    private BreedingInteractionOutcome tryPair(
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> store,
            @Nullable TameworkBreedingComponent sourceBreeding,
            @Nullable TwBreedingConfig config,
            @Nullable CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreedingReadinessPolicy readiness,
            @Nullable Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer>
                    pendingClaims,
            @Nullable Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer>
                    pendingOwners
    ) {
        BreedingPairCandidate candidate = resolveCandidate(
                sourceRef, store, sourceBreeding, config, readiness
        );
        BreedingClaimLimitPolicyService.Decision population = candidate == null
                ? null
                : evaluatePopulation(candidate, config, pendingClaims, pendingOwners);
        if (candidate == null) {
            return BreedingInteractionOutcome.waitingForMate();
        }
        if (population == null) {
            return BreedingInteractionOutcome.integrationUnavailable();
        }
        if (!population.allowed()) {
            if (population.capEnforced()) {
                return BreedingInteractionOutcome.capacityReached();
            }
            return "claim-required".equals(population.reason())
                    ? BreedingInteractionOutcome.claimRequired()
                    : BreedingInteractionOutcome.integrationUnavailable();
        }
        BreedingPairContext context = createContext(candidate, config);
        BreedingPairAdmissionRegistry.Lease admission =
                admissionRegistry.tryAcquire(
                        candidate.store(),
                        context.parentAUuid(),
                        context.parentBUuid()
                );
        if (admission == null) {
            logDebug("Breeding pairing skipped because a parent already has a scheduled birth.");
            return BreedingInteractionOutcome.birthPending();
        }
        BreedingLitterPlanner.Plan litter = litterPlanner.plan(
                candidate.sourceRef(),
                candidate.partnerRef(),
                candidate.store(),
                context,
                config,
                candidate.world().getName()
        );
        if (litter == null) {
            admission.close();
            return BreedingInteractionOutcome.unavailable();
        }
        if (litter.empty()) {
            boolean applied = litterCommit.applyPairEffects(
                    candidate, config, commandBuffer
            );
            admission.close();
            return applied
                    ? BreedingInteractionOutcome.noOffspring()
                    : BreedingInteractionOutcome.unavailable();
        }
        reserveSweepHeadroom(population, pendingClaims, pendingOwners);
        if (litter.admission() == null) {
            if (!litterCommit.applyPairEffects(candidate, config, commandBuffer)) {
                admission.close();
                return BreedingInteractionOutcome.unavailable();
            }
            return scheduleOrdinaryBirth(candidate.world(), context, litter, admission);
        }
        return litterCommit.prepare(
                candidate.world().getName(), context, litter, admission,
                readiness.manualPlayerUuid()
        ) ? BreedingInteractionOutcome.submitted()
                : BreedingInteractionOutcome.unavailable();
    }

    private BreedingInteractionOutcome scheduleOrdinaryBirth(
            World world,
            BreedingPairContext context,
            BreedingLitterPlanner.Plan litter,
            BreedingPairAdmissionRegistry.Lease admission
    ) {
        try {
            delayedEffects.schedule(world, context.parentAUuid(), context.parentBUuid(), () -> {
                try {
                    ordinaryBirth.spawn(world, context, litter);
                } finally {
                    admission.close();
                }
            }, admission::close);
            return BreedingInteractionOutcome.paired();
        } catch (RuntimeException | LinkageError failure) {
            admission.close();
            logDebug("Breeding delayed pairing could not be scheduled.");
            return BreedingInteractionOutcome.unavailable();
        }
    }

    @Nullable
    private BreedingPairCandidate resolveCandidate(
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> store,
            @Nullable TameworkBreedingComponent sourceBreeding,
            @Nullable TwBreedingConfig config,
            @Nonnull BreedingReadinessPolicy readiness
    ) {
        if (sourceRef == null || !sourceRef.isValid()
                || store == null || sourceBreeding == null) {
            return null;
        }
        BreedingPartnerService.PartnerCandidate partner = partnerService.findNearestPartner(
                sourceRef, store, sourceBreeding, config, readiness
        );
        if (partner == null || partner.ref == null || !partner.ref.isValid()) {
            return null;
        }
        return resolveLiveCandidate(
                sourceRef, partner.ref, sourceBreeding, store, readiness
        );
    }

    @Nullable
    private BreedingPairCandidate resolveLiveCandidate(
            @Nonnull Ref<EntityStore> sourceRef,
            @Nonnull Ref<EntityStore> partnerRef,
            @Nonnull TameworkBreedingComponent sourceBreeding,
            @Nonnull Store<EntityStore> store,
            @Nonnull BreedingReadinessPolicy readiness
    ) {
        NPCEntity sourceNpc = store.getComponent(sourceRef, NPCEntity.getComponentType());
        NPCEntity partnerNpc = store.getComponent(partnerRef, NPCEntity.getComponentType());
        TameworkBreedingComponent partnerBreeding = breeding(partnerRef, store);
        World world = resolveWorld(store);
        if (sourceNpc == null || sourceNpc.getUuid() == null
                || partnerNpc == null || partnerNpc.getUuid() == null
                || !BreedingOffspringService.acceptsPartnerReadiness(readiness, partnerBreeding)
                || world == null || !world.isAlive()) {
            return null;
        }
        return new BreedingPairCandidate(
                sourceRef,
                partnerRef,
                sourceNpc,
                partnerNpc,
                sourceBreeding,
                partnerBreeding,
                store,
                world,
                resolveSpawnAnchor(sourceRef, partnerRef, store),
                BreedingOwnerSnapshotResolver.resolve(sourceRef, store),
                BreedingOwnerSnapshotResolver.resolve(partnerRef, store)
        );
    }

    @Nonnull
    private BreedingClaimLimitPolicyService.Decision evaluatePopulation(
            @Nonnull BreedingPairCandidate candidate,
            @Nullable TwBreedingConfig config,
            @Nullable Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer>
                    pendingClaims,
            @Nullable Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer>
                    pendingOwners
    ) {
        BreedingClaimLimitPolicyService.Decision decision = limitPolicy.evaluate(
                candidate.store(),
                candidate.spawnAnchor(),
                config,
                roleId(candidate.sourceNpc()),
                candidate.sourceOwner().ownerId(),
                candidate.partnerOwner().ownerId(),
                pendingClaims,
                pendingOwners
        );
        if (!decision.allowed()) {
            logDebug("Breeding pairing blocked by population limit: " + decision.reason());
        }
        return decision;
    }

    private static void reserveSweepHeadroom(
            @Nonnull BreedingClaimLimitPolicyService.Decision decision,
            @Nullable Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer>
                    pendingClaims,
            @Nullable Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer>
                    pendingOwners
    ) {
        if (!decision.capEnforced()) {
            return;
        }
        int amount = Math.min(
                BreedingFertilityOffspringService.maxOffspringPerBreed(),
                Math.max(0, decision.remainingHeadroom())
        );
        if (amount <= 0) {
            return;
        }
        if (pendingClaims != null && decision.claimReservationKey() != null) {
            pendingClaims.merge(decision.claimReservationKey(), amount, Integer::sum);
        }
        if (pendingOwners != null) {
            for (BreedingClaimLimitPolicyService.PlayerReservationKey key
                    : decision.playerReservationKeys()) {
                pendingOwners.merge(key, amount, Integer::sum);
            }
        }
    }

    @Nonnull
    private BreedingPairContext createContext(
            @Nonnull BreedingPairCandidate candidate,
            @Nullable TwBreedingConfig config
    ) {
        return new BreedingPairContext(
                candidate.sourceNpc().getUuid(),
                candidate.partnerNpc().getUuid(),
                roleId(candidate.sourceNpc()),
                roleId(candidate.partnerNpc()),
                candidate.sourceNpc().getRoleIndex(),
                candidate.partnerNpc().getRoleIndex(),
                candidate.spawnAnchor(),
                candidate.sourceOwner(),
                candidate.partnerOwner(),
                TamedStateResolver.isTamed(candidate.sourceRef(), candidate.store()),
                TamedStateResolver.isTamed(candidate.partnerRef(), candidate.store()),
                resolveConfigId(
                        config, candidate.sourceBreeding(), candidate.partnerBreeding()
                )
        );
    }

    @Nullable
    private static TameworkBreedingComponent breeding(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        ComponentType<EntityStore, TameworkBreedingComponent> type =
                TameworkBreedingComponent.getComponentType();
        return type == null ? null : store.getComponent(ref, type);
    }

    @Nullable
    private static Vector3d resolveSpawnAnchor(
            @Nonnull Ref<EntityStore> parentA,
            @Nonnull Ref<EntityStore> parentB,
            @Nonnull Store<EntityStore> store
    ) {
        TransformComponent a = store.getComponent(parentA, TransformComponent.getComponentType());
        TransformComponent b = store.getComponent(parentB, TransformComponent.getComponentType());
        if (a == null || b == null) {
            return null;
        }
        return new Vector3d(
                (a.getPosition().x + b.getPosition().x) * 0.5,
                Math.max(a.getPosition().y, b.getPosition().y) + SPAWN_HEIGHT_OFFSET,
                (a.getPosition().z + b.getPosition().z) * 0.5
        );
    }

    @Nullable
    private static World resolveWorld(@Nonnull Store<EntityStore> store) {
        return store.getExternalData() == null
                ? null
                : store.getExternalData().getWorld();
    }

    @Nullable
    private static String roleId(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        if (npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
            return npc.getRoleName();
        }
        NPCPlugin plugin = NPCPlugin.get();
        return plugin != null && npc.getRoleIndex() >= 0
                ? plugin.getName(npc.getRoleIndex())
                : null;
    }

    @Nullable
    private static String resolveConfigId(
            @Nullable TwBreedingConfig config,
            @Nonnull TameworkBreedingComponent source,
            @Nonnull TameworkBreedingComponent partner
    ) {
        if (config != null && config.getId() != null && !config.getId().isBlank()) {
            return config.getId();
        }
        if (source.getConfigId() != null && !source.getConfigId().isBlank()) {
            return source.getConfigId();
        }
        return partner.getConfigId() == null || partner.getConfigId().isBlank()
                ? null
                : partner.getConfigId();
    }

    private static void logDebug(@Nullable String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null
                && plugin.isDebugBreedingEnabled()
                && message != null && !message.isBlank()) {
            plugin.getLogger().at(Level.INFO).log(message);
        }
    }

}
