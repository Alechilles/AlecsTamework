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
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import it.unimi.dsi.fastutil.Pair;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Completes pair matching by applying cooldowns and orchestrating offspring spawn.
 */
final class BreedingOffspringService {
    private static final String BREEDING_COOLDOWN_ALARM_NAME = "Breeding_Cooldown";
    private static final String HEARTS_PARTICLE = "Hearts";
    private static final String BREEDING_PAIR_HOOK_ID = "Tamework.Breeding.Pair.Start";
    private static final String BREEDING_PAIR_STATE = "BreedPair";
    private static final String LOCKED_TARGET_SLOT = "LockedTarget";
    private static final long PAIRING_PROXIMITY_CHECK_INTERVAL_MS = 100L;
    private static final long PAIRING_PROXIMITY_TIMEOUT_MS = 5000L;
    private static final long OFFSPRING_SPAWN_DELAY_AFTER_HEARTS_MS = 2200L;
    private static final double APPROACH_SPACING = 0.45;
    private static final double PAIRING_READY_DISTANCE = 2.20;
    private static final double OFFSPRING_SPAWN_HEIGHT_OFFSET = 1.00;
    private static final String BREED_COOLDOWN_MULTIPLIER_EFFECT_KEY = "BreedCooldownMultiplier";

    private final BreedingPartnerService partnerService;
    private final BreedingOffspringSpawnService spawnService;
    private final BreedingFertilityOffspringService fertilityOffspringService;
    private final BreedingOffspringProgressionService progressionService;
    private final BreedingParticleOffsetResolver particleOffsetResolver;
    private final BreedingClaimLimitPolicyService claimLimitPolicyService;

    BreedingOffspringService(BreedingPartnerService partnerService) {
        this.partnerService = partnerService;
        this.spawnService = new BreedingOffspringSpawnService(new BreedingOffspringRoleResolver());
        this.fertilityOffspringService = new BreedingFertilityOffspringService();
        this.progressionService = new BreedingOffspringProgressionService();
        this.particleOffsetResolver = new BreedingParticleOffsetResolver();
        this.claimLimitPolicyService = new BreedingClaimLimitPolicyService();
    }

    boolean tryCompletePairing(Ref<EntityStore> sourceRef,
                               Store<EntityStore> store,
                               TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config) {
        return tryCompletePairing(sourceRef, store, sourceBreeding, config, null, null);
    }

    boolean tryCompletePairing(Ref<EntityStore> sourceRef,
                               Store<EntityStore> store,
                               TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config,
                               @Nullable Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer> pendingClaimReservations) {
        return tryCompletePairing(sourceRef, store, sourceBreeding, config, pendingClaimReservations, null);
    }

    boolean tryCompletePairing(Ref<EntityStore> sourceRef,
                               Store<EntityStore> store,
                               TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config,
                               @Nullable Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer> pendingClaimReservations,
                               @Nullable Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer> pendingPlayerReservations) {
        return tryCompletePairing(
                sourceRef,
                store,
                sourceBreeding,
                config,
                pendingClaimReservations,
                pendingPlayerReservations,
                null
        );
    }

    boolean tryCompletePairing(Ref<EntityStore> sourceRef,
                               Store<EntityStore> store,
                               TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config,
                               @Nullable Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer> pendingClaimReservations,
                               @Nullable Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer> pendingPlayerReservations,
                               @Nullable CommandBuffer<EntityStore> commandBuffer) {
        long now = store != null ? BreedingTimeService.resolveCurrentTimeMs(store) : 0L;
        return tryCompletePairing(
                sourceRef,
                store,
                sourceBreeding,
                config,
                pendingClaimReservations,
                pendingPlayerReservations,
                commandBuffer,
                BreedingReadinessPolicy.passive(now)
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
                null,
                null,
                BreedingReadinessPolicy.manual(playerUuid, ManualBreedingClock.nowMs())
        );
    }

    private boolean tryCompletePairing(Ref<EntityStore> sourceRef,
                                       Store<EntityStore> store,
                                       TameworkBreedingComponent sourceBreeding,
                                       @Nullable TwBreedingConfig config,
                                       @Nullable Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer> pendingClaimReservations,
                                       @Nullable Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer> pendingPlayerReservations,
                                       @Nullable CommandBuffer<EntityStore> commandBuffer,
                                       BreedingReadinessPolicy readinessPolicy) {
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
        BreedingOffspringProgressionService.OwnerSnapshot parentAOwner = resolveOwnerSnapshot(sourceRef, store);
        BreedingOffspringProgressionService.OwnerSnapshot parentBOwner = resolveOwnerSnapshot(partner.ref, store);
        BreedingClaimLimitPolicyService.Decision populationDecision = claimLimitPolicyService.evaluate(
                store,
                spawnAnchor,
                config,
                sourceRoleId,
                parentAOwner.ownerId(),
                parentBOwner.ownerId(),
                pendingClaimReservations,
                pendingPlayerReservations
        );
        if (!populationDecision.allowed()) {
            logInfo(String.format(
                    "Breeding pairing blocked by population limits: reason=%s parentA=%s parentB=%s.",
                    populationDecision.reason(),
                    sourceNpc.getUuid(),
                    partnerNpc.getUuid()
            ));
            return false;
        }

        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        CooldownResolution sourceCooldown = resolveCooldown(config, sourceRef, store);
        CooldownResolution partnerCooldown = resolveCooldown(config, partner.ref, store);
        applyParentCooldown(
                sourceRef,
                sourceBreeding,
                sourceNpc,
                partnerNpc.getUuid(),
                sourceCooldown.durationMs(),
                now,
                store,
                commandBuffer
        );
        applyParentCooldown(
                partner.ref,
                livePartnerBreeding,
                partnerNpc,
                sourceNpc.getUuid(),
                partnerCooldown.durationMs(),
                now,
                store,
                commandBuffer
        );
        logCooldownApplied(sourceNpc, parentAOwner, sourceCooldown);
        logCooldownApplied(partnerNpc, parentBOwner, partnerCooldown);
        moveParentsToPairingPosition(sourceRef, sourceNpc, partner.ref, partnerNpc, store, commandBuffer);

        OffspringSpawnContext context = new OffspringSpawnContext(
                sourceNpc.getUuid(),
                partnerNpc.getUuid(),
                sourceRoleId,
                resolveRoleId(partnerNpc),
                sourceNpc.getRoleIndex(),
                partnerNpc.getRoleIndex(),
                spawnAnchor,
                parentAOwner,
                parentBOwner,
                resolveTamedState(sourceRef, store),
                resolveTamedState(partner.ref, store),
                resolveConfigId(config, sourceBreeding, livePartnerBreeding)
        );
        schedulePairingEffects(context, store);
        if (populationDecision.capEnforced()) {
            int reservationAmount = Math.min(
                    BreedingFertilityOffspringService.maxOffspringPerBreed(),
                    Math.max(0, populationDecision.remainingHeadroom())
            );
            if (reservationAmount > 0) {
                if (pendingClaimReservations != null && populationDecision.claimReservationKey() != null) {
                    pendingClaimReservations.merge(
                            populationDecision.claimReservationKey(),
                            reservationAmount,
                            Integer::sum
                    );
                }
                if (pendingPlayerReservations != null) {
                    for (BreedingClaimLimitPolicyService.PlayerReservationKey ownerKey : populationDecision.playerReservationKeys()) {
                        if (ownerKey == null) {
                            continue;
                        }
                        pendingPlayerReservations.merge(ownerKey, reservationAmount, Integer::sum);
                    }
                }
            }
        }
        return true;
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

    private void applyParentCooldown(Ref<EntityStore> npcRef,
                                     TameworkBreedingComponent breeding,
                                     NPCEntity npc,
                                     UUID partnerUuid,
                                     long cooldownMs,
                                     long now,
                                     Store<EntityStore> store,
                                     @Nullable CommandBuffer<EntityStore> commandBuffer) {
        if (npcRef == null || !npcRef.isValid() || breeding == null || store == null) {
            return;
        }
        long durationMs = Math.max(0L, cooldownMs);
        long until = now + durationMs;
        breeding.setReady(false);
        breeding.setCooldownUntilMs(until);
        breeding.setCooldownStartedAtMs(durationMs > 0L ? now : 0L);
        breeding.setCooldownDurationMs(durationMs);
        breeding.setLastPartnerUuid(partnerUuid);
        breeding.setLastHappinessUpdateMs(now);
        breeding.clearManualBreedingReady();
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type != null) {
            putComponent(npcRef, store, commandBuffer, type, breeding);
        }
        if (npc != null) {
            applyCooldownAlarm(npcRef, npc, until, store);
        }
    }

    private void applyCooldownAlarm(Ref<EntityStore> npcRef,
                                    NPCEntity npc,
                                    long cooldownUntilMs,
                                    Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || npc == null || store == null || cooldownUntilMs == 0L) {
            return;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return;
        }
        Alarm alarm = alarmStore.get(npc, BREEDING_COOLDOWN_ALARM_NAME);
        if (alarm == null) {
            return;
        }
        alarm.set(npcRef, Instant.ofEpochMilli(cooldownUntilMs), store);
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
            Vector3d axis = new Vector3d(b).subtract(a);
            if (axis.squaredLength() > 0.00001) {
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

    private void spawnHeartsParticle(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        NPCEntity npcEntity = store.getComponent(npcRef, NPCEntity.getComponentType());
        Vector3d position = new Vector3d(transform.getPosition());
        Vector3d offset = particleOffsetResolver.resolveOffset(npcEntity);
        position.x += offset.x;
        position.y += offset.y;
        position.z += offset.z;
        ParticleUtil.spawnParticleEffect(HEARTS_PARTICLE, position, store);
    }

    @Nonnull
    private CooldownResolution resolveCooldown(@Nullable TwBreedingConfig config,
                                               Ref<EntityStore> npcRef,
                                               Store<EntityStore> store) {
        String roleId = null;
        if (npcRef != null && npcRef.isValid() && store != null) {
            roleId = resolveRoleId(store.getComponent(npcRef, NPCEntity.getComponentType()));
        }
        TwBreedingConfig.CooldownSettings settings = config != null
                ? config.resolveCooldowns(roleId)
                : null;
        int baseSeconds = settings != null ? Math.max(0, settings.getBaseCooldownSeconds()) : 600;
        int minDelay = settings != null ? Math.max(0, settings.getMinDelaySeconds()) : 15;
        int maxDelay = settings != null ? Math.max(0, settings.getMaxDelaySeconds()) : 45;
        if (maxDelay < minDelay) {
            int swap = minDelay;
            minDelay = maxDelay;
            maxDelay = swap;
        }
        int randomDelay = maxDelay > minDelay
                ? ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1)
                : minDelay;
        double baseSecondsWithDelay = (double) baseSeconds + (double) randomDelay;
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                BREED_COOLDOWN_MULTIPLIER_EFFECT_KEY,
                1.0
        );
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        double adjustedSeconds = baseSecondsWithDelay * multiplier;
        TwBreedingConfig.TimerBasis timerBasis = config != null
                ? config.resolveTiming(roleId).getTimerBasis()
                : TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED;
        long durationMs = BreedingTimeService.toGameDurationMs(adjustedSeconds, timerBasis, store);
        double currentRate = BreedingTimeService.resolveCurrentGameSecondsPerRealSecond(store);
        double baselineRate = BreedingTimeService.resolveBaselineGameSecondsPerRealSecond(store);
        double approximateRealSeconds = estimateRealSeconds(durationMs, currentRate);
        return new CooldownResolution(
                baseSeconds,
                randomDelay,
                multiplier,
                adjustedSeconds,
                timerBasis,
                durationMs,
                currentRate,
                baselineRate,
                approximateRealSeconds
        );
    }

    private static double estimateRealSeconds(long gameDurationMs, double currentRate) {
        if (gameDurationMs <= 0L || !Double.isFinite(currentRate) || currentRate <= 0.0) {
            return 0.0;
        }
        return (double) gameDurationMs / (currentRate * 1000.0);
    }

    private void logCooldownApplied(@Nullable NPCEntity npc,
                                    @Nullable BreedingOffspringProgressionService.OwnerSnapshot owner,
                                    @Nonnull CooldownResolution cooldown) {
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
            return;
        }
        scheduleWorldAction(
                world,
                PAIRING_PROXIMITY_CHECK_INTERVAL_MS,
                "pairing-await-proximity",
                () -> awaitPairingProximity(world, context, PAIRING_PROXIMITY_CHECK_INTERVAL_MS)
        );
    }

    private void awaitPairingProximity(World world, OffspringSpawnContext context, long elapsedMs) {
        if (world == null || context == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore() != null
                ? world.getEntityStore().getStore()
                : null;
        if (store == null) {
            return;
        }
        Ref<EntityStore> parentARef = world.getEntityRef(context.parentAUuid());
        Ref<EntityStore> parentBRef = world.getEntityRef(context.parentBUuid());
        boolean ready = isPairingReady(parentARef, parentBRef, store);
        if (ready || elapsedMs >= PAIRING_PROXIMITY_TIMEOUT_MS) {
            if (!ready) {
                logInfo(String.format(
                        "Breeding pairing proximity timeout after %dms: parentA=%s parentB=%s.",
                        elapsedMs,
                        context.parentAUuid(),
                        context.parentBUuid()
                ));
            }
            spawnPairedHearts(world, context);
            scheduleWorldAction(
                    world,
                    OFFSPRING_SPAWN_DELAY_AFTER_HEARTS_MS,
                    "offspring-spawn",
                    () -> spawnOffspring(world, context)
            );
            return;
        }
        long nextElapsed = elapsedMs + PAIRING_PROXIMITY_CHECK_INTERVAL_MS;
        scheduleWorldAction(
                world,
                PAIRING_PROXIMITY_CHECK_INTERVAL_MS,
                "pairing-await-proximity",
                () -> awaitPairingProximity(world, context, nextElapsed)
        );
    }

    private boolean isPairingReady(@Nullable Ref<EntityStore> parentARef,
                                   @Nullable Ref<EntityStore> parentBRef,
                                   Store<EntityStore> store) {
        TransformComponent parentATransform = getTransform(parentARef, store);
        TransformComponent parentBTransform = getTransform(parentBRef, store);
        if (parentATransform == null || parentBTransform == null) {
            return false;
        }
        double distance = parentATransform.getPosition().distanceTo(parentBTransform.getPosition());
        return Double.isFinite(distance) && distance <= PAIRING_READY_DISTANCE;
    }

    private void scheduleWorldAction(World world, long delayMs, String actionLabel, Runnable action) {
        if (world == null || action == null) {
            return;
        }
        long safeDelayMs = Math.max(0L, delayMs);
        CompletableFuture.runAsync(() -> executeWorldAction(world, actionLabel, action),
                CompletableFuture.delayedExecutor(safeDelayMs, TimeUnit.MILLISECONDS))
                .exceptionally(ex -> {
                    logWarn("Breeding delayed action failed asynchronously: " + actionLabel + ".", ex);
                    return null;
                });
    }

    private void executeWorldAction(World world, @Nullable String actionLabel, Runnable action) {
        if (world == null || action == null) {
            return;
        }
        String label = actionLabel == null || actionLabel.isBlank() ? "unknown-action" : actionLabel;
        try {
            world.execute(() -> {
                try {
                    action.run();
                } catch (Throwable ex) {
                    logWarn("Breeding delayed action failed during world execution: " + label + ".", ex);
                }
            });
        } catch (Throwable ex) {
            logWarn("Breeding delayed action failed before world execution: " + label + ".", ex);
        }
    }

    private void spawnPairedHearts(World world, OffspringSpawnContext context) {
        if (world == null || context == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return;
        }
        Ref<EntityStore> parentARef = world.getEntityRef(context.parentAUuid());
        Ref<EntityStore> parentBRef = world.getEntityRef(context.parentBUuid());
        spawnHeartsParticle(parentARef, store);
        spawnHeartsParticle(parentBRef, store);
    }

    private void spawnOffspring(World world, OffspringSpawnContext context) {
        if (world == null || context == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return;
        }
        Ref<EntityStore> parentARef = world.getEntityRef(context.parentAUuid());
        Ref<EntityStore> parentBRef = world.getEntityRef(context.parentBUuid());
        BreedingFertilityOffspringService.FertilityRoll fertilityRoll =
                fertilityOffspringService.rollOffspring(parentARef, parentBRef, store);
        if (fertilityRoll.offspringCount() <= 0) {
            logInfo(String.format(
                    "Breeding produced no offspring: parentA=%s parentB=%s fertilityA=%.2f fertilityB=%.2f expected=%.2f.",
                    context.parentAUuid(),
                    context.parentBUuid(),
                    fertilityRoll.parentAMultiplier(),
                    fertilityRoll.parentBMultiplier(),
                    fertilityRoll.expectedOffspring()
            ));
            return;
        }
        TransformComponent parentATransform = getTransform(parentARef, store);
        TransformComponent parentBTransform = getTransform(parentBRef, store);
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return;
        }
        String baseRoleId = resolveBaseRoleId(context, parentARef, parentBRef, store);
        String parentARoleId = firstNonBlank(context.parentARoleId(), resolveRoleId(parentARef, store));
        String parentBRoleId = firstNonBlank(context.parentBRoleId(), resolveRoleId(parentBRef, store));
        if (parentARoleId == null || parentARoleId.isBlank()) {
            parentARoleId = baseRoleId;
        }
        TwBreedingConfig childBreedingConfig = resolveBreedingConfig(context.breedingConfigId());
        BreedingOffspringSpawnService.ResolvedSpawnRole spawnRole = spawnService.resolveSpawnRole(
                parentARoleId,
                parentBRoleId,
                childBreedingConfig,
                context.parentARoleIndex(),
                context.parentBRoleIndex(),
                npcPlugin,
                Math.random(),
                Math.random()
        );
        if (spawnRole == null) {
            logWarn(String.format(
                    "Breeding spawn skipped: unable to resolve offspring role (parentA=%s, parentB=%s).",
                    context.parentAUuid(),
                    context.parentBUuid()
            ));
            return;
        }
        Vector3d spawnPosition = resolveSpawnPosition(parentATransform, parentBTransform, context.spawnAnchor());
        if (spawnPosition == null) {
            logWarn(String.format(
                    "Breeding spawn skipped: no spawn position (parentA=%s, parentB=%s).",
                    context.parentAUuid(),
                    context.parentBUuid()
            ));
            return;
        }
        BreedingClaimLimitPolicyService.Decision populationDecision = claimLimitPolicyService.evaluate(
                store,
                spawnPosition,
                childBreedingConfig,
                spawnRole.roleId(),
                context.parentAOwner().ownerId(),
                context.parentBOwner().ownerId(),
                null,
                null
        );
        if (!populationDecision.allowed()) {
            logInfo(String.format(
                    "Breeding spawn skipped by population limits: reason=%s parentA=%s parentB=%s role=%s.",
                    populationDecision.reason(),
                    context.parentAUuid(),
                    context.parentBUuid(),
                    spawnRole.roleId()
            ));
            return;
        }
        int targetSpawnCount = fertilityRoll.offspringCount();
        if (populationDecision.capEnforced()) {
            targetSpawnCount = Math.min(targetSpawnCount, populationDecision.remainingHeadroom());
            if (targetSpawnCount <= 0) {
                logInfo(String.format(
                        "Breeding spawn skipped: population cap reached parentA=%s parentB=%s role=%s cap=%d current=%d pending=%d.",
                        context.parentAUuid(),
                        context.parentBUuid(),
                        spawnRole.roleId(),
                        populationDecision.effectiveCap(),
                        populationDecision.currentCount(),
                        populationDecision.pendingReservations()
                ));
                return;
            }
            if (targetSpawnCount < fertilityRoll.offspringCount()) {
                logInfo(String.format(
                        "Breeding offspring clamped by population cap: requested=%d allowed=%d parentA=%s parentB=%s role=%s cap=%d current=%d.",
                        fertilityRoll.offspringCount(),
                        targetSpawnCount,
                        context.parentAUuid(),
                        context.parentBUuid(),
                        spawnRole.roleId(),
                        populationDecision.effectiveCap(),
                        populationDecision.currentCount()
                ));
            }
        }
        Vector3f spawnRotation = resolveSpawnRotation(parentATransform, parentBTransform);
        int spawnedCount = 0;
        for (int i = 0; i < targetSpawnCount; i++) {
            BreedingOffspringSpawnService.ResolvedSpawnRole childSpawnRole = i == 0
                    ? spawnRole
                    : spawnService.resolveSpawnRole(
                            parentARoleId,
                            parentBRoleId,
                            childBreedingConfig,
                            context.parentARoleIndex(),
                            context.parentBRoleIndex(),
                            npcPlugin,
                            Math.random(),
                            Math.random()
                    );
            if (childSpawnRole == null) {
                logWarn(String.format(
                        "Breeding spawn skipped: unable to resolve offspring role for child %d (parentA=%s, parentB=%s).",
                        i + 1,
                        context.parentAUuid(),
                        context.parentBUuid()
                ));
                continue;
            }
            Vector3d spawnAttemptPosition = i == 0
                    ? spawnPosition
                    : new Vector3d(
                            spawnPosition.x + ThreadLocalRandom.current().nextDouble(-0.55, 0.55),
                            spawnPosition.y,
                            spawnPosition.z + ThreadLocalRandom.current().nextDouble(-0.55, 0.55)
                    );
            Pair<Ref<EntityStore>, NPCEntity> spawned = spawnService.spawnWithFallback(
                    npcPlugin,
                    store,
                    childSpawnRole.roleIndex(),
                    spawnAttemptPosition,
                    spawnRotation
            );
            if (spawned == null || spawned.first() == null || spawned.second() == null) {
                logWarn(String.format(
                        "Breeding spawn failed after fallback attempts: role=%s index=%d parentA=%s parentB=%s pos=(%.2f, %.2f, %.2f).",
                        childSpawnRole.roleId(),
                        childSpawnRole.roleIndex(),
                        context.parentAUuid(),
                        context.parentBUuid(),
                        spawnAttemptPosition.x,
                        spawnAttemptPosition.y,
                        spawnAttemptPosition.z
                ));
                continue;
            }
            Ref<EntityStore> childRef = spawned.first();
            NPCEntity childNpc = spawned.second();
            CooldownResolution childCooldown = resolveCooldown(childBreedingConfig, childRef, store);
            progressionService.applyOffspringState(
                    childRef,
                    childNpc,
                    parentARef,
                    parentBRef,
                    childSpawnRole.roleId(),
                    context.parentAOwner(),
                    context.parentBOwner(),
                    context.parentATamed(),
                    context.parentBTamed(),
                    context.breedingConfigId(),
                    childCooldown.durationMs(),
                    childSpawnRole.adultRoleId(),
                    childSpawnRole.gender(),
                    childSpawnRole.lifecycleFamily(),
                    store
            );
            spawnHeartsParticle(childRef, store);
            BreedingOffspringProgressionService.OwnerSnapshot childOwner = resolveOwnerSnapshot(childRef, store);
            logCooldownApplied(childNpc, childOwner, childCooldown);
            logInfo(String.format(
                    "Breeding spawn success: child=%s role=%s parentA=%s parentB=%s offspringOwner=%s.",
                    childNpc.getUuid(),
                    childSpawnRole.roleId(),
                    context.parentAUuid(),
                    context.parentBUuid(),
                    describeOwner(childOwner)
            ));
            spawnedCount++;
        }
        if (spawnedCount > 1) {
            logInfo(String.format(
                    "Breeding produced multiple offspring: count=%d parentA=%s parentB=%s fertilityA=%.2f fertilityB=%.2f expected=%.2f.",
                    spawnedCount,
                    context.parentAUuid(),
                    context.parentBUuid(),
                    fertilityRoll.parentAMultiplier(),
                    fertilityRoll.parentBMultiplier(),
                    fertilityRoll.expectedOffspring()
            ));
        }
        if (spawnedCount > 0) {
            CompanionLevelingService.awardBreedingXp(parentARef, store);
            CompanionLevelingService.awardBreedingXp(parentBRef, store);
        }
    }

    @Nullable
    private TransformComponent getTransform(@Nullable Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        return store.getComponent(npcRef, TransformComponent.getComponentType());
    }

    @Nullable
    private String resolveBaseRoleId(OffspringSpawnContext context,
                                     @Nullable Ref<EntityStore> parentARef,
                                     @Nullable Ref<EntityStore> parentBRef,
                                     Store<EntityStore> store) {
        String fromContext = context.parentARoleId();
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        fromContext = context.parentBRoleId();
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        String fromStore = resolveRoleId(parentARef, store);
        if (fromStore != null && !fromStore.isBlank()) {
            return fromStore;
        }
        return resolveRoleId(parentBRef, store);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
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

    private Vector3f resolveSpawnRotation(@Nullable TransformComponent parentATransform,
                                          @Nullable TransformComponent parentBTransform) {
        if (parentATransform == null && parentBTransform == null) {
            return new Vector3f();
        }
        if (parentATransform != null && parentBTransform != null) {
            Vector3d delta = new Vector3d(parentBTransform.getPosition()).subtract(parentATransform.getPosition());
            if (delta.squaredLength() > 0.00001) {
                return Vector3f.lookAt(delta);
            }
        }
        TransformComponent fallback = parentATransform != null ? parentATransform : parentBTransform;
        return new Vector3f(fallback.getRotation());
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

    private void logWarn(String message, Throwable error) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null || message == null || message.isBlank()) {
            return;
        }
        if (error == null) {
            instance.getLogger().at(Level.WARNING).log(message);
            return;
        }
        instance.getLogger().at(Level.WARNING).withCause(error).log(message);
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

    private record CooldownResolution(int baseSeconds,
                                      int randomDelaySeconds,
                                      double traitMultiplier,
                                      double configuredSeconds,
                                      TwBreedingConfig.TimerBasis basis,
                                      long durationMs,
                                      double currentRate,
                                      double baselineRate,
                                      double approximateRealSeconds) {
    }

    private record OffspringSpawnContext(UUID parentAUuid,
                                         UUID parentBUuid,
                                         @Nullable String parentARoleId,
                                         @Nullable String parentBRoleId,
                                         int parentARoleIndex,
                                         int parentBRoleIndex,
                                         @Nullable Vector3d spawnAnchor,
                                         BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
                                         BreedingOffspringProgressionService.OwnerSnapshot parentBOwner,
                                         boolean parentATamed,
                                         boolean parentBTamed,
                                         @Nullable String breedingConfigId) {
    }
}
