package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
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
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import it.unimi.dsi.fastutil.Pair;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Completes pair matching by applying cooldowns and orchestrating offspring spawn.
 */
final class BreedingOffspringService {
    private static final String BREEDING_COOLDOWN_ALARM_NAME = "Breeding_Cooldown";
    private static final String HEARTS_PARTICLE = "Hearts";
    private static final long HEARTS_PARTICLE_DELAY_MS = 850L;
    private static final long OFFSPRING_SPAWN_DELAY_AFTER_HEARTS_MS = 1200L;
    private static final double APPROACH_SPACING = 0.45;

    private final BreedingPartnerService partnerService;
    private final BreedingOffspringRoleResolver roleResolver;
    private final BreedingOffspringProgressionService progressionService;

    BreedingOffspringService(BreedingPartnerService partnerService) {
        this.partnerService = partnerService;
        this.roleResolver = new BreedingOffspringRoleResolver();
        this.progressionService = new BreedingOffspringProgressionService();
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

        long now = System.currentTimeMillis();
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
        moveNpcToTarget(parentANpc, parentARef, targets.parentATarget(), store);
        moveNpcToTarget(parentBNpc, parentBRef, targets.parentBTarget(), store);
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
        long baseMs = (long) (baseSeconds + randomDelay) * 1000L;
        double multiplier = TraitModifierService.resolveMultiplier(npcRef, store, "BreedCooldownMultiplier", 1.0);
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        long adjusted = Math.round(baseMs * multiplier);
        return Math.max(0L, adjusted);
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
                HEARTS_PARTICLE_DELAY_MS,
                () -> spawnPairedHearts(world, context)
        );
        scheduleWorldAction(
                world,
                HEARTS_PARTICLE_DELAY_MS + OFFSPRING_SPAWN_DELAY_AFTER_HEARTS_MS,
                () -> spawnOffspring(world, context)
        );
    }

    private void scheduleWorldAction(World world, long delayMs, Runnable action) {
        if (world == null || action == null) {
            return;
        }
        long safeDelayMs = Math.max(0L, delayMs);
        CompletableFuture.runAsync(
                () -> world.execute(action),
                CompletableFuture.delayedExecutor(safeDelayMs, TimeUnit.MILLISECONDS)
        );
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
        TransformComponent parentATransform = getTransform(parentARef, store);
        TransformComponent parentBTransform = getTransform(parentBRef, store);
        if (parentATransform == null && parentBTransform == null) {
            return;
        }

        String baseRoleId = resolveBaseRoleId(context, parentARef, parentBRef, store);
        if (baseRoleId == null || baseRoleId.isBlank()) {
            return;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return;
        }
        BreedingOffspringRoleResolver.OffspringRoleSelection roleSelection =
                roleResolver.selectOffspringRole(baseRoleId, npcPlugin);
        if (roleSelection == null || roleSelection.roleId() == null || roleSelection.roleId().isBlank()) {
            return;
        }
        int roleIndex = npcPlugin.getIndex(roleSelection.roleId());
        if (roleIndex < 0) {
            return;
        }

        Vector3d spawnPosition = resolveSpawnPosition(parentATransform, parentBTransform);
        Vector3f spawnRotation = resolveSpawnRotation(parentATransform, parentBTransform);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, spawnPosition, spawnRotation, null, null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            return;
        }
        Ref<EntityStore> childRef = spawned.first();
        NPCEntity childNpc = spawned.second();
        TwBreedingConfig childBreedingConfig = resolveBreedingConfig(context.breedingConfigId());
        long childCooldownMs = resolveCooldownMs(childBreedingConfig, childRef, store);
        progressionService.applyOffspringState(
                childRef,
                childNpc,
                parentARef,
                parentBRef,
                roleSelection.roleId(),
                context.parentAOwner(),
                context.parentBOwner(),
                context.parentATamed(),
                context.parentBTamed(),
                context.breedingConfigId(),
                childCooldownMs,
                roleSelection.hasBabyVariant(),
                store
        );
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

    private Vector3d resolveSpawnPosition(@Nullable TransformComponent parentATransform,
                                          @Nullable TransformComponent parentBTransform) {
        if (parentATransform != null && parentBTransform != null) {
            Vector3d a = parentATransform.getPosition();
            Vector3d b = parentBTransform.getPosition();
            return new Vector3d(
                    (a.x + b.x) * 0.5,
                    Math.max(a.y, b.y) + 0.1,
                    (a.z + b.z) * 0.5
            );
        }
        TransformComponent source = parentATransform != null ? parentATransform : parentBTransform;
        Vector3d base = source.getPosition();
        double offsetX = ThreadLocalRandom.current().nextDouble(-0.6, 0.6);
        double offsetZ = ThreadLocalRandom.current().nextDouble(-0.6, 0.6);
        return new Vector3d(base.x + offsetX, base.y + 0.1, base.z + offsetZ);
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

    private record PairingTargets(Vector3d parentATarget, Vector3d parentBTarget) {
    }

    private record OffspringSpawnContext(UUID parentAUuid,
                                         UUID parentBUuid,
                                         @Nullable String parentARoleId,
                                         @Nullable String parentBRoleId,
                                         BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
                                         BreedingOffspringProgressionService.OwnerSnapshot parentBOwner,
                                         boolean parentATamed,
                                         boolean parentBTamed,
                                         @Nullable String breedingConfigId) {
    }
}
