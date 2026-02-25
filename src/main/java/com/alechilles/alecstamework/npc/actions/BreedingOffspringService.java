package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Completes pair matching by applying cooldowns and orchestrating offspring spawn.
 */
final class BreedingOffspringService {
    private static final String BREEDING_COOLDOWN_ALARM_NAME = "Breeding_Cooldown";
    private static final String HEARTS_PARTICLE = "Hearts";
    private static final String BREEDING_PAIR_HOOK_ID = "Tamework.Breeding.Pair.Start";
    private static final String BREEDING_PAIR_STATE = "BreedPair";
    private static final long PAIRING_PROXIMITY_CHECK_INTERVAL_MS = 100L;
    private static final long PAIRING_PROXIMITY_TIMEOUT_MS = 5000L;
    private static final long OFFSPRING_SPAWN_DELAY_AFTER_HEARTS_MS = 2200L;
    private static final double APPROACH_SPACING = 0.45;
    private static final double PAIRING_READY_DISTANCE = 2.20;
    private static final double OFFSPRING_SPAWN_HEIGHT_OFFSET = 1.00;

    private final BreedingPartnerService partnerService;
    private final BreedingOffspringSpawnService spawnService;
    private final BreedingFertilityOffspringService fertilityOffspringService;
    private final BreedingOffspringProgressionService progressionService;
    private final BreedingOffspringPresenceProbeService presenceProbeService;

    BreedingOffspringService(BreedingPartnerService partnerService) {
        this.partnerService = partnerService;
        this.spawnService = new BreedingOffspringSpawnService(new BreedingOffspringRoleResolver());
        this.fertilityOffspringService = new BreedingFertilityOffspringService();
        this.progressionService = new BreedingOffspringProgressionService();
        this.presenceProbeService = new BreedingOffspringPresenceProbeService();
    }

    boolean tryCompletePairing(Ref<EntityStore> sourceRef,
                               Store<EntityStore> store,
                               TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config) {
        if (sourceRef == null || !sourceRef.isValid() || store == null || sourceBreeding == null) {
            return false;
        }
        BreedingPartnerService.PartnerCandidate partner = partnerService.findNearestPartner(
                sourceRef,
                store,
                sourceBreeding,
                config
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
        if (livePartnerBreeding == null || !livePartnerBreeding.isReady()) {
            return false;
        }

        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        long sourceCooldownMs = resolveCooldownMs(config, sourceRef, store);
        long partnerCooldownMs = resolveCooldownMs(config, partner.ref, store);
        applyParentCooldown(sourceRef, sourceBreeding, sourceNpc, partnerNpc.getUuid(), sourceCooldownMs, now, store);
        applyParentCooldown(partner.ref, livePartnerBreeding, partnerNpc, sourceNpc.getUuid(), partnerCooldownMs, now, store);
        moveParentsToPairingPosition(sourceRef, sourceNpc, partner.ref, partnerNpc, store);

        OffspringSpawnContext context = new OffspringSpawnContext(
                sourceNpc.getUuid(),
                partnerNpc.getUuid(),
                resolveRoleId(sourceNpc),
                resolveRoleId(partnerNpc),
                sourceNpc.getRoleIndex(),
                partnerNpc.getRoleIndex(),
                resolveSpawnAnchor(sourceRef, partner.ref, store),
                resolveOwnerSnapshot(sourceRef, store),
                resolveOwnerSnapshot(partner.ref, store),
                resolveTamedState(sourceRef, store),
                resolveTamedState(partner.ref, store),
                resolveConfigId(config, sourceBreeding, livePartnerBreeding)
        );
        schedulePairingEffects(context, store);
        return true;
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
                                     Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || breeding == null || store == null) {
            return;
        }
        long until = now + Math.max(0L, cooldownMs);
        breeding.setReady(false);
        breeding.setCooldownUntilMs(until);
        breeding.setLastPartnerUuid(partnerUuid);
        breeding.setLastHappinessUpdateMs(now);
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type != null) {
            store.putComponent(npcRef, type, breeding);
        }
        if (npc != null) {
            applyCooldownAlarm(npcRef, npc, until, store);
        }
    }

    private void applyCooldownAlarm(Ref<EntityStore> npcRef,
                                    NPCEntity npc,
                                    long cooldownUntilMs,
                                    Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || npc == null || store == null || cooldownUntilMs <= 0L) {
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
                                              Store<EntityStore> store) {
        TransformComponent parentATransform = getTransform(parentARef, store);
        TransformComponent parentBTransform = getTransform(parentBRef, store);
        if (parentATransform == null && parentBTransform == null) {
            return;
        }
        PairingTargets targets = resolvePairingTargets(parentATransform, parentBTransform);
        moveNpcToPairingTarget(parentANpc, parentARef, targets.parentATarget(), store);
        moveNpcToPairingTarget(parentBNpc, parentBRef, targets.parentBTarget(), store);
    }

    private void moveNpcToPairingTarget(@Nullable NPCEntity npc,
                                        @Nullable Ref<EntityStore> npcRef,
                                        @Nullable Vector3d target,
                                        @Nullable Store<EntityStore> store) {
        if (npc == null || npcRef == null || !npcRef.isValid() || store == null || target == null) {
            return;
        }
        if (applyBreedingPairHook(npc, npcRef, target, store)) {
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
                                          @Nullable Store<EntityStore> store) {
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
        store.putComponent(npcRef, hookType, new TameworkHookComponent(
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
        Vector3d position = new Vector3d(transform.getPosition());
        position.y += 1.0;
        ParticleUtil.spawnParticleEffect(HEARTS_PARTICLE, position, store);
    }

    private long resolveCooldownMs(@Nullable TwBreedingConfig config,
                                   Ref<EntityStore> npcRef,
                                   Store<EntityStore> store) {
        TwBreedingConfig.CooldownSettings settings = config != null ? config.getCooldowns() : null;
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
        double multiplier = TraitModifierService.resolveMultiplier(npcRef, store, "BreedCooldownMultiplier", 1.0);
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        double adjustedSeconds = baseSecondsWithDelay * multiplier;
        TwBreedingConfig.TimerBasis timerBasis = config != null
                ? config.getTiming().getTimerBasis()
                : TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED;
        return BreedingTimeService.toGameDurationMs(adjustedSeconds, timerBasis, store);
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
        TwBreedingConfig childBreedingConfig = resolveBreedingConfig(context.breedingConfigId());
        BreedingOffspringSpawnService.ResolvedSpawnRole spawnRole = spawnService.resolveSpawnRole(
                baseRoleId,
                childBreedingConfig,
                context.parentARoleIndex(),
                context.parentBRoleIndex(),
                npcPlugin
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
        Vector3f spawnRotation = resolveSpawnRotation(parentATransform, parentBTransform);
        int spawnedCount = 0;
        for (int i = 0; i < fertilityRoll.offspringCount(); i++) {
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
                    spawnRole.roleIndex(),
                    spawnAttemptPosition,
                    spawnRotation
            );
            if (spawned == null || spawned.first() == null || spawned.second() == null) {
                logWarn(String.format(
                        "Breeding spawn failed after fallback attempts: role=%s index=%d parentA=%s parentB=%s pos=(%.2f, %.2f, %.2f).",
                        spawnRole.roleId(),
                        spawnRole.roleIndex(),
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
            long childCooldownMs = resolveCooldownMs(childBreedingConfig, childRef, store);
            progressionService.applyOffspringState(
                    childRef,
                    childNpc,
                    parentARef,
                    parentBRef,
                    spawnRole.roleId(),
                    context.parentAOwner(),
                    context.parentBOwner(),
                    context.parentATamed(),
                    context.parentBTamed(),
                    context.breedingConfigId(),
                    childCooldownMs,
                    spawnRole.lifecycleFamily(),
                    store
            );
            spawnHeartsParticle(childRef, store);
            logInfo(String.format(
                    "Breeding spawn success: child=%s role=%s parentA=%s parentB=%s.",
                    childNpc.getUuid(),
                    spawnRole.roleId(),
                    context.parentAUuid(),
                    context.parentBUuid()
            ));
            presenceProbeService.schedulePresenceChecks(
                    world,
                    childNpc.getUuid(),
                    spawnRole.roleId(),
                    context.parentAUuid(),
                    context.parentBUuid()
            );
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
        if (ownerType == null) {
            return BreedingOffspringProgressionService.OwnerSnapshot.empty();
        }
        TameworkOwnerComponent ownerComponent = store.getComponent(npcRef, ownerType);
        if (ownerComponent == null) {
            return BreedingOffspringProgressionService.OwnerSnapshot.empty();
        }
        return new BreedingOffspringProgressionService.OwnerSnapshot(
                ownerComponent.getOwnerId(),
                ownerComponent.getOwnerName()
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
        if (instance == null || instance.getLogger() == null || message == null || message.isBlank()) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(message);
    }

    private record PairingTargets(Vector3d parentATarget, Vector3d parentBTarget) {
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
