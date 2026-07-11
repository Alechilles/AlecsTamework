package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.ownership.BreedingBirthPlanSnapshot;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionRequest;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.BreedingPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.BreedingPopulationReplayState;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import com.alechilles.alecstamework.ownership.OwnerComponentMutationService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import it.unimi.dsi.fastutil.Pair;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Completes pair matching by applying cooldowns and orchestrating offspring spawn.
 */
final class BreedingOffspringService {
    private static final String BREEDING_PAIR_HOOK_ID = "Tamework.Breeding.Pair.Start";
    private static final String BREEDING_PAIR_STATE = "BreedPair";
    private static final String LOCKED_TARGET_SLOT = "LockedTarget";
    private static final double APPROACH_SPACING = 0.45;
    private static final double OFFSPRING_SPAWN_HEIGHT_OFFSET = 1.00;

    private final BreedingPartnerService partnerService;
    private final BreedingOffspringSpawnService spawnService;
    private final BreedingFertilityOffspringService fertilityOffspringService;
    private final BreedingCooldownService cooldownService;
    private final BreedingPairingEffectsService pairingEffectsService;
    private final BreedingBirthPlanService birthPlanService;
    private final BreedingBirthPlanReplayService birthPlanReplayService;
    private final BreedingParentCooldownService parentCooldownService;
    private final BreedingPairAdmissionRegistry pairAdmissionRegistry;
    private final BreedingNearbyReservationService nearbyReservationService;
    private final BreedingOffspringPostSpawnService postSpawnService;
    private final BreedingPreparedPairingHandoffService pairingHandoffService;

    BreedingOffspringService(BreedingPartnerService partnerService) {
        this.partnerService = partnerService;
        this.spawnService = new BreedingOffspringSpawnService(new BreedingOffspringRoleResolver());
        this.fertilityOffspringService = new BreedingFertilityOffspringService();
        this.cooldownService = new BreedingCooldownService();
        this.pairingEffectsService = new BreedingPairingEffectsService(new BreedingParticleOffsetResolver());
        this.birthPlanService = new BreedingBirthPlanService(fertilityOffspringService, spawnService);
        this.birthPlanReplayService = new BreedingBirthPlanReplayService();
        this.parentCooldownService = new BreedingParentCooldownService(cooldownService);
        this.pairAdmissionRegistry = BreedingPairAdmissionRegistry.shared();
        this.nearbyReservationService = BreedingNearbyReservationService.shared();
        this.postSpawnService = new BreedingOffspringPostSpawnService(
                new BreedingOffspringProgressionService(),
                cooldownService,
                pairingEffectsService
        );
        this.pairingHandoffService = new BreedingPreparedPairingHandoffService(
                nearbyReservationService, pairAdmissionRegistry, this::logWarn, this::logInfo
        );
    }

    boolean tryCompletePairing(Ref<EntityStore> sourceRef,
                               Store<EntityStore> store,
                               TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config) {
        return tryCompletePairing(sourceRef, store, sourceBreeding, config, null);
    }

    boolean tryCompletePairing(Ref<EntityStore> sourceRef,
                               Store<EntityStore> store,
                               TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config,
                               @Nullable CommandBuffer<EntityStore> commandBuffer) {
        long now = store != null ? BreedingTimeService.resolveCurrentTimeMs(store) : 0L;
        return tryCompletePairing(
                sourceRef,
                store,
                sourceBreeding,
                config,
                commandBuffer,
                BreedingReadinessPolicy.passive(now),
                null
        );
    }

    boolean tryCompletePairing(Ref<EntityStore> sourceRef,
                               Store<EntityStore> store,
                               TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config,
                               @Nullable CommandBuffer<EntityStore> commandBuffer,
                               @Nonnull BreedingPopulationSweepContext populationContext) {
        long now = store != null ? BreedingTimeService.resolveCurrentTimeMs(store) : 0L;
        return tryCompletePairing(
                sourceRef,
                store,
                sourceBreeding,
                config,
                commandBuffer,
                BreedingReadinessPolicy.passive(now),
                populationContext
        );
    }

    boolean tryCompleteManualPairing(Ref<EntityStore> sourceRef,
                                     Store<EntityStore> store,
                                     TameworkBreedingComponent sourceBreeding,
                                     @Nullable TwBreedingConfig config,
                                     UUID playerUuid) {
        return tryCompletePairing(
                sourceRef,
                store,
                sourceBreeding,
                config,
                null,
                BreedingReadinessPolicy.manual(playerUuid, ManualBreedingClock.nowMs()),
                null
        );
    }

    private boolean tryCompletePairing(Ref<EntityStore> sourceRef,
                                       Store<EntityStore> store,
                                       TameworkBreedingComponent sourceBreeding,
                                       @Nullable TwBreedingConfig config,
                                       @Nullable CommandBuffer<EntityStore> commandBuffer,
                                       BreedingReadinessPolicy readinessPolicy,
                                       @Nullable BreedingPopulationSweepContext populationContext) {
        if (sourceRef == null || !sourceRef.isValid() || store == null || sourceBreeding == null) {
            return false;
        }
        BreedingPartnerService.PartnerCandidate partner = partnerService.findNearestPartner(
                sourceRef,
                store,
                sourceBreeding,
                config,
                readinessPolicy
        );
        if (partner == null || partner.ref == null || !partner.ref.isValid()) {
            return false;
        }

        NPCEntity sourceNpc = store.getComponent(sourceRef, NPCEntity.getComponentType());
        NPCEntity partnerNpc = store.getComponent(partner.ref, NPCEntity.getComponentType());
        if (sourceNpc == null || sourceNpc.getUuid() == null || partnerNpc == null || partnerNpc.getUuid() == null) {
            return false;
        }
        TameworkBreedingComponent livePartnerBreeding = getBreedingComponent(partner.ref, store);
        if (!acceptsPartnerReadiness(readinessPolicy, livePartnerBreeding)) {
            return false;
        }
        Vector3d spawnAnchor = resolveSpawnAnchor(sourceRef, partner.ref, store);
        String sourceRoleId = resolveRoleId(sourceNpc);
        String partnerRoleId = resolveRoleId(partnerNpc);
        BreedingOffspringProgressionService.OwnerSnapshot parentAOwner = resolveOwnerSnapshot(sourceRef, store);
        BreedingOffspringProgressionService.OwnerSnapshot parentBOwner = resolveOwnerSnapshot(partner.ref, store);
        BreedingPairAdmissionRegistry.Token pairToken = pairAdmissionRegistry.tryReserve(
                sourceNpc.getUuid(),
                partnerNpc.getUuid(),
                sourceBreeding.getCooldownUntilMs(),
                livePartnerBreeding.getCooldownUntilMs()
        );
        if (pairToken == null) {
            return false;
        }
        BreedingPopulationAdmissionService populationService = resolvePopulationAdmissionService();
        if (populationService == null) {
            pairAdmissionRegistry.cancel(pairToken);
            return false;
        }
        String attemptKey = BreedingPopulationAdmissionRequestFactory.attemptKey(pairToken);
        BreedingPopulationReplayState replay = populationService.replayState(attemptKey);
        Runnable replayCooldownNow = () -> {
            if (replay.hasCommittedChildren()) {
                applyParentCooldowns(
                        sourceRef, sourceBreeding, sourceNpc, parentAOwner,
                        partner.ref, livePartnerBreeding, partnerNpc, parentBOwner,
                        config, store, commandBuffer
                );
            }
        };
        BreedingBirthPlanReplayService.Resolution planResolution;
        try {
            planResolution = birthPlanReplayService.resolve(
                    replay,
                    () -> birthPlanService.plan(
                            sourceRef, partner.ref, store, sourceRoleId, partnerRoleId,
                            sourceNpc.getRoleIndex(), partnerNpc.getRoleIndex(), config,
                            parentAOwner, parentBOwner
                    ),
                    config
            );
        } catch (RuntimeException | LinkageError failure) {
            replayCooldownNow.run();
            pairAdmissionRegistry.cancel(pairToken);
            return false;
        }
        if (!planResolution.allowed()
                || planResolution.fullPlan() == null
                || planResolution.missingPlan() == null
                || planResolution.snapshot() == null) {
            replayCooldownNow.run();
            pairAdmissionRegistry.cancel(pairToken);
            logWarn("Breeding retry plan was rejected: reason=" + planResolution.reason() + ".");
            return false;
        }
        BreedingBirthPlan fullBirthPlan = planResolution.fullPlan();
        BreedingBirthPlan birthPlan = planResolution.missingPlan();
        BreedingBirthPlanSnapshot birthPlanSnapshot = planResolution.snapshot();
        if (birthPlan.children().isEmpty()) {
            if (planResolution.committedCount() > 0) {
                replayCooldownNow.run();
                pairAdmissionRegistry.complete(pairToken);
                logInfo("Breeding retry found every planned child already committed; parent cooldowns were restored.");
                return true;
            }
            if (fullBirthPlan.fertility().offspringCount() <= 0) {
                completeNaturalZeroPairing(
                        sourceRef,
                        sourceBreeding,
                        sourceNpc,
                        partner.ref,
                        livePartnerBreeding,
                        partnerNpc,
                        config,
                        parentAOwner,
                        parentBOwner,
                        store,
                        commandBuffer
                );
                pairAdmissionRegistry.complete(pairToken);
                return true;
            }
            pairAdmissionRegistry.cancel(pairToken);
            return false;
        }
        World world = store.getExternalData() == null ? null : store.getExternalData().getWorld();
        String worldName = world == null ? null : world.getName();
        if (world == null || worldName == null || worldName.isBlank() || spawnAnchor == null) {
            replayCooldownNow.run();
            pairAdmissionRegistry.cancel(pairToken);
            return false;
        }
        BreedingNearbyReservationService.Reservation nearbyReservation =
                nearbyReservationService.reserve(
                        store,
                        worldName,
                        spawnAnchor,
                        config,
                        sourceRoleId,
                        birthPlan.children()
                );
        if (nearbyReservation.admittedCount() <= 0) {
            replayCooldownNow.run();
            pairAdmissionRegistry.cancel(pairToken);
            return false;
        }
        BreedingPopulationAdmissionRequest admissionRequest = BreedingPopulationAdmissionRequestFactory.create(
                worldName,
                spawnAnchor,
                birthPlan,
                birthPlanSnapshot,
                nearbyReservation,
                pairToken
        );
        String breedingConfigId = resolveConfigId(config, sourceBreeding, livePartnerBreeding);
        Runnable replayAbortCompletion = planResolution.committedCount() <= 0
                ? () -> { }
                : () -> parentCooldownService.applyDeferred(
                        world,
                        pairToken.parentA(),
                        pairToken.parentB(),
                        parentAOwner,
                        parentBOwner,
                        breedingConfigId,
                        this::resolveBreedingConfig,
                        this::logCooldownApplied,
                        this::logWarn
                );
        try {
            BreedingPopulationAdmissionService.PreparationContext preparationContext =
                    populationContext == null ? null : populationContext.resolve(populationService);
            CompletableFuture<BreedingPopulationPreparationResult> preparation =
                    populationService.prepareAsync(admissionRequest, preparationContext);
            if (preparation == null) {
                replayCooldownNow.run();
                pairingHandoffService.releaseUnprepared(nearbyReservation, pairToken);
                return false;
            }
            preparation.whenComplete((result, failure) -> pairingHandoffService.dispatch(
                    world,
                    populationService,
                    pairToken,
                    nearbyReservation,
                    result,
                    failure,
                    replayAbortCompletion,
                    (batch, terminality) -> finalizePairingAdmission(
                            world, populationService, pairToken, nearbyReservation,
                            birthPlan, batch, terminality, spawnAnchor,
                            parentAOwner, parentBOwner,
                            resolveTamedState(sourceRef, store),
                            resolveTamedState(partner.ref, store),
                            breedingConfigId,
                            planResolution.committedCount()
                    )
            ));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            replayCooldownNow.run();
            pairingHandoffService.releaseUnprepared(nearbyReservation, pairToken);
            return false;
        }
    }

    private void completeNaturalZeroPairing(
            Ref<EntityStore> sourceRef,
            TameworkBreedingComponent sourceBreeding,
            NPCEntity sourceNpc,
            Ref<EntityStore> partnerRef,
            TameworkBreedingComponent partnerBreeding,
            NPCEntity partnerNpc,
            @Nullable TwBreedingConfig config,
            BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
            BreedingOffspringProgressionService.OwnerSnapshot parentBOwner,
            Store<EntityStore> store,
            @Nullable CommandBuffer<EntityStore> commandBuffer
    ) {
        applyParentCooldowns(
                sourceRef, sourceBreeding, sourceNpc, parentAOwner,
                partnerRef, partnerBreeding, partnerNpc, parentBOwner,
                config, store, commandBuffer
        );
        pairingEffectsService.spawnHearts(sourceRef, store);
        pairingEffectsService.spawnHearts(partnerRef, store);
    }

    private boolean applyParentCooldowns(
            Ref<EntityStore> parentARef,
            TameworkBreedingComponent parentABreeding,
            NPCEntity parentA,
            BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
            Ref<EntityStore> parentBRef,
            TameworkBreedingComponent parentBBreeding,
            NPCEntity parentB,
            BreedingOffspringProgressionService.OwnerSnapshot parentBOwner,
            @Nullable TwBreedingConfig config,
            Store<EntityStore> store,
            @Nullable CommandBuffer<EntityStore> commandBuffer
    ) {
        try {
            parentCooldownService.apply(
                    parentARef, parentABreeding, parentA, parentAOwner,
                    parentBRef, parentBBreeding, parentB, parentBOwner,
                    config, store, commandBuffer, this::logCooldownApplied
            );
            return true;
        } catch (RuntimeException | LinkageError failure) {
            logWarn("Breeding parent cooldown application failed.");
            return false;
        }
    }

    @Nullable
    private BreedingPopulationAdmissionService resolvePopulationAdmissionService() {
        Tamework instance = Tamework.getInstance();
        return instance == null || instance.getOwnerPopulationRuntime() == null
                ? null
                : instance.getOwnerPopulationRuntime().breedingAdmissionService();
    }

    private void finalizePairingAdmission(
            World world,
            BreedingPopulationAdmissionService populationService,
            BreedingPairAdmissionRegistry.Token pairToken,
            BreedingNearbyReservationService.Reservation nearbyReservation,
            BreedingBirthPlan birthPlan,
            PreparedBreedingPopulationBatch populationBatch,
            BreedingPreparedHandoffTerminality terminality,
            Vector3d spawnAnchor,
            BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
            BreedingOffspringProgressionService.OwnerSnapshot parentBOwner,
            boolean parentATamed,
            boolean parentBTamed,
            @Nullable String breedingConfigId,
            int replayedCommittedCount
    ) {
        Store<EntityStore> store = world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        Ref<EntityStore> sourceRef = world.getEntityRef(pairToken.parentA());
        Ref<EntityStore> partnerRef = world.getEntityRef(pairToken.parentB());
        if (store == null || sourceRef == null || !sourceRef.isValid()
                || partnerRef == null || !partnerRef.isValid()) {
            terminality.cancel("breeding-parents-unavailable");
            return;
        }
        NPCEntity sourceNpc = store.getComponent(sourceRef, NPCEntity.getComponentType());
        NPCEntity partnerNpc = store.getComponent(partnerRef, NPCEntity.getComponentType());
        TameworkBreedingComponent sourceBreeding = getBreedingComponent(sourceRef, store);
        TameworkBreedingComponent partnerBreeding = getBreedingComponent(partnerRef, store);
        if (sourceNpc == null || partnerNpc == null || sourceBreeding == null || partnerBreeding == null) {
            terminality.cancel("breeding-parent-components-unavailable");
            return;
        }
        int admitted = populationBatch.admittedCount();
        nearbyReservationService.releaseFrom(nearbyReservation, admitted);
        BreedingBirthPlan admittedPlan = new BreedingBirthPlan(
                birthPlan.fertility(),
                birthPlan.children().subList(0, admitted)
        );
        moveParentsToPairingPosition(sourceRef, sourceNpc, partnerRef, partnerNpc, store, null);
        OffspringSpawnContext context = new OffspringSpawnContext(
                pairToken.parentA(),
                pairToken.parentB(),
                spawnAnchor,
                parentAOwner,
                parentBOwner,
                parentATamed,
                parentBTamed,
                breedingConfigId,
                resolveRoleId(sourceNpc),
                admittedPlan,
                populationBatch,
                nearbyReservation,
                pairToken,
                populationService,
                terminality,
                replayedCommittedCount
        );
        schedulePairingEffects(context, store);
    }

    static boolean acceptsPartnerReadiness(@Nullable BreedingReadinessPolicy readinessPolicy,
                                           @Nullable TameworkBreedingComponent breeding) {
        if (breeding == null) {
            return false;
        }
        if (readinessPolicy != null) {
            return readinessPolicy.accepts(breeding);
        }
        return breeding.isReady();
    }

    @Nullable
    private TameworkBreedingComponent getBreedingComponent(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type == null || npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        return store.getComponent(npcRef, type);
    }

    private void moveParentsToPairingPosition(Ref<EntityStore> parentARef,
                                              NPCEntity parentANpc,
                                              Ref<EntityStore> parentBRef,
                                              NPCEntity parentBNpc,
                                              Store<EntityStore> store,
                                              @Nullable CommandBuffer<EntityStore> commandBuffer) {
        setPairLookTarget(parentARef, parentANpc, parentBRef, store);
        setPairLookTarget(parentBRef, parentBNpc, parentARef, store);
        TransformComponent parentATransform = getTransform(parentARef, store);
        TransformComponent parentBTransform = getTransform(parentBRef, store);
        if (parentATransform == null && parentBTransform == null) {
            return;
        }
        PairingTargets targets = resolvePairingTargets(parentATransform, parentBTransform);
        moveNpcToPairingTarget(parentANpc, parentARef, targets.parentATarget(), store, commandBuffer);
        moveNpcToPairingTarget(parentBNpc, parentBRef, targets.parentBTarget(), store, commandBuffer);
    }

    private void setPairLookTarget(@Nullable Ref<EntityStore> sourceRef,
                                   @Nullable NPCEntity sourceNpc,
                                   @Nullable Ref<EntityStore> targetRef,
                                   @Nullable Store<EntityStore> store) {
        if (store == null
                || sourceRef == null
                || !sourceRef.isValid()
                || sourceNpc == null
                || targetRef == null
                || !targetRef.isValid()
                || sourceRef.equals(targetRef)) {
            return;
        }
        Role role = sourceNpc.getRole();
        if (role == null || role.getMarkedEntitySupport() == null) {
            return;
        }
        role.getMarkedEntitySupport().setMarkedEntity(LOCKED_TARGET_SLOT, targetRef);
    }

    private void moveNpcToPairingTarget(@Nullable NPCEntity npc,
                                        @Nullable Ref<EntityStore> npcRef,
                                        @Nullable Vector3d target,
                                        @Nullable Store<EntityStore> store,
                                        @Nullable CommandBuffer<EntityStore> commandBuffer) {
        if (npc == null || npcRef == null || !npcRef.isValid() || store == null || target == null) {
            return;
        }
        if (applyBreedingPairHook(npc, npcRef, target, store, commandBuffer)) {
            return;
        }
        moveNpcToTarget(npc, npcRef, target, store);
    }

    private void moveNpcToTarget(@Nullable NPCEntity npc,
                                 @Nullable Ref<EntityStore> npcRef,
                                 @Nullable Vector3d target,
                                 @Nullable Store<EntityStore> store) {
        if (npc == null || npcRef == null || !npcRef.isValid() || store == null || target == null) {
            return;
        }
        npc.moveTo(npcRef, target.x, target.y, target.z, store);
    }

    private boolean applyBreedingPairHook(@Nullable NPCEntity npc,
                                          @Nullable Ref<EntityStore> npcRef,
                                          @Nullable Vector3d target,
                                          @Nullable Store<EntityStore> store,
                                          @Nullable CommandBuffer<EntityStore> commandBuffer) {
        if (npc == null || npcRef == null || !npcRef.isValid() || target == null || store == null) {
            return false;
        }
        if (!supportsBreedingPairState(npc)) {
            return false;
        }
        ComponentType<EntityStore, TameworkHookComponent> hookType = TameworkHookComponent.getComponentType();
        if (hookType == null) {
            return false;
        }
        putComponent(npcRef, store, commandBuffer, hookType, new TameworkHookComponent(
                BREEDING_PAIR_HOOK_ID,
                null,
                null,
                null,
                System.currentTimeMillis(),
                true,
                target
        ));
        return true;
    }

    private <T extends Component<EntityStore>> void putComponent(@Nonnull Ref<EntityStore> npcRef,
                                                                 @Nonnull Store<EntityStore> store,
                                                                 @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                                 @Nonnull ComponentType<EntityStore, T> componentType,
                                                                 @Nonnull T component) {
        if (commandBuffer != null) {
            commandBuffer.putComponent(npcRef, componentType, component);
            return;
        }
        store.putComponent(npcRef, componentType, component);
    }

    private boolean supportsBreedingPairState(@Nullable NPCEntity npc) {
        if (npc == null) {
            return false;
        }
        Role role = npc.getRole();
        if (role == null || role.getStateSupport() == null || role.getStateSupport().getStateHelper() == null) {
            return false;
        }
        int stateIndex = role.getStateSupport().getStateHelper().getStateIndex(BREEDING_PAIR_STATE);
        return stateIndex != StateSupport.NO_STATE;
    }

    private PairingTargets resolvePairingTargets(@Nullable TransformComponent parentATransform,
                                                 @Nullable TransformComponent parentBTransform) {
        if (parentATransform != null && parentBTransform != null) {
            Vector3d a = parentATransform.getPosition();
            Vector3d b = parentBTransform.getPosition();
            double targetY = Math.max(a.y, b.y);
            Vector3d midpoint = new Vector3d((a.x + b.x) * 0.5, targetY, (a.z + b.z) * 0.5);
            Vector3d axis = new Vector3d(b).sub(a);
            if (axis.lengthSquared() > 0.00001) {
                axis.normalize();
                Vector3d targetA = new Vector3d(
                        midpoint.x - axis.x * APPROACH_SPACING,
                        targetY,
                        midpoint.z - axis.z * APPROACH_SPACING
                );
                Vector3d targetB = new Vector3d(
                        midpoint.x + axis.x * APPROACH_SPACING,
                        targetY,
                        midpoint.z + axis.z * APPROACH_SPACING
                );
                return new PairingTargets(targetA, targetB);
            }
            return new PairingTargets(
                    new Vector3d(midpoint.x - APPROACH_SPACING, midpoint.y, midpoint.z),
                    new Vector3d(midpoint.x + APPROACH_SPACING, midpoint.y, midpoint.z)
            );
        }
        TransformComponent source = parentATransform != null ? parentATransform : parentBTransform;
        Vector3d base = source.getPosition();
        double offsetX = ThreadLocalRandom.current().nextDouble(-APPROACH_SPACING, APPROACH_SPACING);
        double offsetZ = ThreadLocalRandom.current().nextDouble(-APPROACH_SPACING, APPROACH_SPACING);
        Vector3d target = new Vector3d(base.x + offsetX, base.y, base.z + offsetZ);
        return new PairingTargets(target, target);
    }

    private void logCooldownApplied(@Nullable NPCEntity npc,
                                    @Nullable BreedingOffspringProgressionService.OwnerSnapshot owner,
                                    @Nonnull BreedingCooldownService.Resolution cooldown) {
        if (npc == null || npc.getUuid() == null) {
            return;
        }
        logInfo(String.format(
                "Breeding cooldown applied: npc=%s owner=%s basis=%s base=%ds random=%ds traitMult=%.3f configured=%.2fs gameMs=%d realApprox=%.2fs rateCurrent=%.4f rateBaseline=%.4f.",
                npc.getUuid(),
                describeOwner(owner),
                cooldown.basis(),
                cooldown.baseSeconds(),
                cooldown.randomDelaySeconds(),
                cooldown.traitMultiplier(),
                cooldown.configuredSeconds(),
                cooldown.durationMs(),
                cooldown.approximateRealSeconds(),
                cooldown.currentRate(),
                cooldown.baselineRate()
        ));
    }

    private static String describeOwner(@Nullable BreedingOffspringProgressionService.OwnerSnapshot owner) {
        if (owner == null || owner.ownerId() == null) {
            return "<none>";
        }
        String ownerName = owner.ownerName();
        if (ownerName == null || ownerName.isBlank()) {
            return owner.ownerId().toString();
        }
        return ownerName + " (" + owner.ownerId() + ")";
    }

    private void schedulePairingEffects(OffspringSpawnContext context, Store<EntityStore> sourceStore) {
        World world = sourceStore != null && sourceStore.getExternalData() != null
                ? sourceStore.getExternalData().getWorld()
                : null;
        if (world == null) {
            context.terminality().cancel("breeding-effects-world-unavailable");
            return;
        }
        try {
            pairingEffectsService.schedule(
                    world,
                    context.parentAUuid(),
                    context.parentBUuid(),
                    () -> spawnOffspring(world, context),
                    () -> context.terminality().cancel("breeding-pairing-effects-canceled")
            );
        } catch (RuntimeException | LinkageError failure) {
            context.terminality().cancel("breeding-pairing-effects-failed");
        }
    }

    private void spawnOffspring(World world, OffspringSpawnContext context) {
        if (context == null) {
            return;
        }
        if (world == null) {
            context.terminality().cancel("breeding-spawn-world-unavailable");
            return;
        }
        if (!pairAdmissionRegistry.claimSpawn(context.pairToken())) {
            context.terminality().cancel("breeding-pair-claim-failed");
            return;
        }
        Store<EntityStore> store = world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        Ref<EntityStore> parentARef = world.getEntityRef(context.parentAUuid());
        Ref<EntityStore> parentBRef = world.getEntityRef(context.parentBUuid());
        BreedingPopulationAdmissionService populationService = context.populationService();
        if (store == null
                || parentARef == null || !parentARef.isValid()
                || parentBRef == null || !parentBRef.isValid()) {
            context.terminality().cancel("breeding-spawn-context-unavailable");
            return;
        }
        TransformComponent parentATransform = getTransform(parentARef, store);
        TransformComponent parentBTransform = getTransform(parentBRef, store);
        Vector3d spawnPosition = resolveSpawnPosition(
                parentATransform,
                parentBTransform,
                context.spawnAnchor()
        );
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (spawnPosition == null || npcPlugin == null) {
            context.terminality().cancel("breeding-spawn-runtime-unavailable");
            return;
        }
        Rotation3f spawnRotation = resolveSpawnRotation(parentATransform, parentBTransform);
        int spawnedCount = 0;
        List<BreedingBirthPlan.PlannedChild> children = context.birthPlan().children();
        boolean[] terminalUnits = new boolean[children.size()];
        if (!context.terminality().transferToSpawn()) {
            return;
        }
        try {
        for (int index = 0; index < children.size(); index++) {
            BreedingBirthPlan.PlannedChild childPlan = children.get(index);
            TwBreedingConfig currentConfig = resolveBreedingConfig(context.breedingConfigId());
            if (!nearbyReservationService.claimForSpawn(
                    context.nearbyReservation(), index, store, world.getName(), spawnPosition,
                    currentConfig, context.sourceRoleId(), childPlan)) {
                cancelBreedingUnit(populationService, context, index,
                        "breeding-nearby-recheck-failed");
                terminalUnits[index] = true;
                continue;
            }
            if (!populationService.claimForSpawn(context.populationBatch(), index)) {
                cancelBreedingUnit(populationService, context, index,
                        "breeding-population-recheck-failed");
                terminalUnits[index] = true;
                continue;
            }
            Vector3d attemptPosition = index == 0
                    ? spawnPosition
                    : new Vector3d(
                            spawnPosition.x + ThreadLocalRandom.current().nextDouble(-0.55, 0.55),
                            spawnPosition.y,
                            spawnPosition.z + ThreadLocalRandom.current().nextDouble(-0.55, 0.55)
                    );
            int unitIndex = index;
            BreedingPreparedSpawnResult spawn = spawnService.spawnPreparedWithFallback(
                    npcPlugin,
                    store,
                    childPlan.spawnRole().roleIndex(),
                    attemptPosition,
                    spawnRotation,
                    context.populationBatch().populationBatch()
                            .admission(index)
                            .claimReservation()
                            .destinationChunk(),
                    context.populationBatch().child(index).plannedNpcUuid(),
                    (npc, holder, spawnStore) -> {
                        OwnerComponentMutationService.WriteResult write =
                                populationService.writeSpawnHolder(
                                        context.populationBatch(),
                                        unitIndex,
                                        holder
                                );
                        return write.applied() ? null : write.reason();
                    }
            );
            if (spawn.spawned() == null) {
                if (spawn.outcomeAmbiguous()) {
                    terminalUnits[index] = true;
                    preserveAmbiguousBreedingUnit(populationService, context, index, spawn.reason());
                    continue;
                }
                cancelBreedingUnit(populationService, context, index,
                        spawn.reason() == null ? "breeding-spawn-failed" : spawn.reason());
                terminalUnits[index] = true;
                continue;
            }
            Ref<EntityStore> childRef = spawn.spawned().first();
            NPCEntity childNpc = spawn.spawned().second();
            UUID childUuid = context.populationBatch().child(index).plannedNpcUuid();
            terminalUnits[index] = true;
            spawnedCount++;
            try {
            postSpawnService.finish(
                    new BreedingOffspringPostSpawnService.Request(
                            childRef,
                            childNpc,
                            parentARef,
                            parentBRef,
                            store,
                            currentConfig,
                            childPlan.spawnRole().roleId(),
                            childUuid,
                            context.breedingConfigId(),
                            context.parentAOwner(),
                            context.parentBOwner(),
                            context.parentATamed(),
                            context.parentBTamed(),
                            childPlan.spawnRole().adultRoleId(),
                            childPlan.spawnRole().gender(),
                            childPlan.spawnRole().lifecycleFamily(),
                            cooldown -> logCooldownApplied(childNpc, childPlan.owner(), cooldown),
                            physicsReset -> logInfo(String.format(
                                    "Breeding spawn success: child=%s role=%s parentA=%s parentB=%s offspringOwner=%s spawnPhysicsReset={%s}.",
                                    childUuid,
                                    childPlan.spawnRole().roleId(),
                                    context.parentAUuid(),
                                    context.parentBUuid(),
                                    describeOwner(childPlan.owner()),
                                    physicsReset
                            ))
                    ),
                    () -> populationService.commitAsync(
                            context.populationBatch(), unitIndex
                    ),
                    reason -> logWarn(
                            "Breeding live-child finalization degraded for child="
                                    + childUuid + " reason=" + reason + "."
                    ),
                    () -> nearbyReservationService.releaseUnit(
                            context.nearbyReservation(), unitIndex
                    )
            );
            } catch (RuntimeException | LinkageError failure) {
                populationService.commitAsync(context.populationBatch(), unitIndex);
                logWarn("Breeding post-spawn continuation failed for live child="
                        + childUuid + "; population commit was forced.");
            }
        }
        } catch (RuntimeException | LinkageError failure) {
            logWarn("Breeding litter aborted by an unexpected exception; remaining admissions were canceled.");
        } finally {
            for (int index = 0; index < terminalUnits.length; index++) {
                if (!terminalUnits[index]) {
                    cancelBreedingUnit(populationService, context, index,
                            "breeding-litter-exception");
                }
            }
            pairAdmissionRegistry.complete(context.pairToken());
        }
        if (spawnedCount > 0 || context.replayedCommittedCount() > 0) {
            applySuccessfulPairCooldowns(parentARef, parentBRef, context, store);
        }
        if (spawnedCount > 0) {
            CompanionLevelingService.awardBreedingXp(parentARef, store);
            CompanionLevelingService.awardBreedingXp(parentBRef, store);
        }
        logInfo(String.format(
                "Breeding litter completed: requested=%d admitted=%d replayed=%d spawned=%d parentA=%s parentB=%s.",
                context.birthPlan().fertility().offspringCount(),
                children.size(),
                context.replayedCommittedCount(),
                spawnedCount,
                context.parentAUuid(),
                context.parentBUuid()
        ));
    }

    private void preserveAmbiguousBreedingUnit(
            @Nonnull BreedingPopulationAdmissionService populationService,
            @Nonnull OffspringSpawnContext context,
            int unitIndex,
            @Nullable String reason
    ) {
        populationService.markReadinessDegraded("breeding_spawn_outcome_ambiguous");
        try {
            nearbyReservationService.releaseUnit(context.nearbyReservation(), unitIndex);
        } catch (RuntimeException | LinkageError ignored) {
            // Nearby reservations have a bounded lease fallback.
        }
        logWarn("Breeding spawn outcome was ambiguous for unit=" + unitIndex
                + " reason=" + (reason == null ? "unknown" : reason)
                + "; APPLYING journal retained for startup recovery.");
    }

    private void cancelBreedingUnit(
            @Nonnull BreedingPopulationAdmissionService populationService,
            @Nonnull OffspringSpawnContext context,
            int unitIndex,
            @Nonnull String reason
    ) {
        try {
            populationService.cancelAsync(context.populationBatch(), unitIndex, reason);
        } catch (RuntimeException | LinkageError failure) {
            logWarn("Breeding population cancellation failed for unit=" + unitIndex + ".");
        }
        try {
            nearbyReservationService.releaseUnit(context.nearbyReservation(), unitIndex);
        } catch (RuntimeException | LinkageError ignored) {
            // Nearby reservations have a bounded lease fallback.
        }
    }

    private boolean applySuccessfulPairCooldowns(@Nonnull Ref<EntityStore> parentARef,
                                                 @Nonnull Ref<EntityStore> parentBRef,
                                                 @Nonnull OffspringSpawnContext context,
                                                 @Nonnull Store<EntityStore> store) {
        NPCEntity parentA = store.getComponent(parentARef, NPCEntity.getComponentType());
        NPCEntity parentB = store.getComponent(parentBRef, NPCEntity.getComponentType());
        TameworkBreedingComponent breedingA = getBreedingComponent(parentARef, store);
        TameworkBreedingComponent breedingB = getBreedingComponent(parentBRef, store);
        if (parentA == null || parentB == null || breedingA == null || breedingB == null) {
            return false;
        }
        TwBreedingConfig config = resolveBreedingConfig(context.breedingConfigId());
        return applyParentCooldowns(
                parentARef, breedingA, parentA, context.parentAOwner(),
                parentBRef, breedingB, parentB, context.parentBOwner(),
                config, store, null
        );
    }
    private TransformComponent getTransform(@Nullable Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        return store.getComponent(npcRef, TransformComponent.getComponentType());
    }

    @Nullable
    private Vector3d resolveSpawnPosition(@Nullable TransformComponent parentATransform,
                                          @Nullable TransformComponent parentBTransform,
                                          @Nullable Vector3d fallbackAnchor) {
        if (parentATransform != null && parentBTransform != null) {
            Vector3d a = parentATransform.getPosition();
            Vector3d b = parentBTransform.getPosition();
            return new Vector3d(
                    (a.x + b.x) * 0.5,
                    Math.max(a.y, b.y) + OFFSPRING_SPAWN_HEIGHT_OFFSET,
                    (a.z + b.z) * 0.5
            );
        }
        if (parentATransform != null || parentBTransform != null) {
            TransformComponent source = parentATransform != null ? parentATransform : parentBTransform;
            Vector3d base = source.getPosition();
            double offsetX = ThreadLocalRandom.current().nextDouble(-0.6, 0.6);
            double offsetZ = ThreadLocalRandom.current().nextDouble(-0.6, 0.6);
            return new Vector3d(base.x + offsetX, base.y + OFFSPRING_SPAWN_HEIGHT_OFFSET, base.z + offsetZ);
        }
        if (fallbackAnchor == null) {
            return null;
        }
        return new Vector3d(fallbackAnchor.x, fallbackAnchor.y + OFFSPRING_SPAWN_HEIGHT_OFFSET, fallbackAnchor.z);
    }

    private Rotation3f resolveSpawnRotation(@Nullable TransformComponent parentATransform,
                                            @Nullable TransformComponent parentBTransform) {
        if (parentATransform == null && parentBTransform == null) {
            return new Rotation3f();
        }
        if (parentATransform != null && parentBTransform != null) {
            Vector3d delta = new Vector3d(parentBTransform.getPosition()).sub(parentATransform.getPosition());
            if (delta.lengthSquared() > 0.00001) {
                return Rotation3f.lookAt(delta);
            }
        }
        TransformComponent fallback = parentATransform != null ? parentATransform : parentBTransform;
        return new Rotation3f(fallback.getRotation());
    }

    @Nullable
    private Vector3d resolveSpawnAnchor(@Nullable Ref<EntityStore> parentARef,
                                        @Nullable Ref<EntityStore> parentBRef,
                                        @Nullable Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        TransformComponent parentATransform = getTransform(parentARef, store);
        TransformComponent parentBTransform = getTransform(parentBRef, store);
        return resolveSpawnPosition(parentATransform, parentBTransform, null);
    }

    private TwBreedingConfig resolveBreedingConfig(@Nullable String configId) {
        if (configId != null && !configId.isBlank()) {
            TwBreedingConfig resolved = TwBreedingConfig.resolveById(configId);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private String resolveConfigId(@Nullable TwBreedingConfig config,
                                   TameworkBreedingComponent source,
                                   TameworkBreedingComponent partner) {
        if (config != null && config.getId() != null && !config.getId().isBlank()) {
            return config.getId();
        }
        if (source.getConfigId() != null && !source.getConfigId().isBlank()) {
            return source.getConfigId();
        }
        if (partner.getConfigId() != null && !partner.getConfigId().isBlank()) {
            return partner.getConfigId();
        }
        return null;
    }

    private BreedingOffspringProgressionService.OwnerSnapshot resolveOwnerSnapshot(Ref<EntityStore> npcRef,
                                                                                   Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return BreedingOffspringProgressionService.OwnerSnapshot.empty();
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent ownerComponent = ownerType != null ? store.getComponent(npcRef, ownerType) : null;
        UUID ownerId = ownerComponent != null ? ownerComponent.getOwnerId() : null;
        if (ownerId == null) {
            ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
            TameworkCommandLinksComponent linksComponent = linksType != null ? store.getComponent(npcRef, linksType) : null;
            if (linksComponent != null) {
                ownerId = linksComponent.getOwnerId();
            }
        }
        if (ownerId == null) {
            return BreedingOffspringProgressionService.OwnerSnapshot.empty();
        }
        return new BreedingOffspringProgressionService.OwnerSnapshot(
                ownerId,
                ownerComponent != null ? ownerComponent.getOwnerName() : null
        );
    }

    private boolean resolveTamedState(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        return TamedStateResolver.isTamed(npcRef, store);
    }

    @Nullable
    private String resolveRoleId(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0 && NPCPlugin.get() != null) {
            String resolved = NPCPlugin.get().getName(roleIndex);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return null;
    }

    @Nullable
    private String resolveRoleId(@Nullable Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        return resolveRoleId(store.getComponent(npcRef, NPCEntity.getComponentType()));
    }

    private void logWarn(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null || message == null || message.isBlank()) {
            return;
        }
        instance.getLogger().at(Level.WARNING).log(message);
    }

    private void logInfo(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance == null
                || instance.getLogger() == null
                || !instance.isDebugBreedingEnabled()
                || message == null
                || message.isBlank()) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(message);
    }

    private record PairingTargets(Vector3d parentATarget, Vector3d parentBTarget) {
    }

    private record OffspringSpawnContext(UUID parentAUuid,
                                         UUID parentBUuid,
                                         @Nullable Vector3d spawnAnchor,
                                         BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
                                          BreedingOffspringProgressionService.OwnerSnapshot parentBOwner,
                                           boolean parentATamed,
                                           boolean parentBTamed,
                                           @Nullable String breedingConfigId,
                                           @Nullable String sourceRoleId,
                                           BreedingBirthPlan birthPlan,
                                          PreparedBreedingPopulationBatch populationBatch,
                                          BreedingNearbyReservationService.Reservation nearbyReservation,
                                          BreedingPairAdmissionRegistry.Token pairToken,
                                          BreedingPopulationAdmissionService populationService,
                                          BreedingPreparedHandoffTerminality terminality,
                                          int replayedCommittedCount) {
    }
}
