package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.math.TameworkRotationUtil;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Resolves and spawns a litter from current world state after the pairing animation.
 *
 * <p>Capacity is checked against the released SimpleClaims scan and the current
 * nearby same-type population immediately before the litter is spawned.
 */
final class BreedingOffspringBirthService {
    private static final double SPAWN_HEIGHT_OFFSET = 1.0;

    private final BreedingOffspringSpawnService spawnService =
            new BreedingOffspringSpawnService(new BreedingOffspringRoleResolver());
    private final BreedingClaimLimitPolicyService limitPolicy;
    private final BreedingPopulationTypeService populationTypeService =
            new BreedingPopulationTypeService();
    private final BreedingNearbyPopulationAllowance nearbyAllowance =
            new BreedingNearbyPopulationAllowance();
    private final BreedingCooldownService cooldownService =
            new BreedingCooldownService();
    private final BreedingPairingEffectsService effectsService =
            new BreedingPairingEffectsService(new BreedingParticleOffsetResolver());
    private final BreedingOffspringPostSpawnService postSpawnService =
            new BreedingOffspringPostSpawnService(
                    new BreedingOffspringProgressionService(),
                    cooldownService,
                    effectsService
            );
    private final BreedingSpawnCompletionGuard completionGuard =
            new BreedingSpawnCompletionGuard();

    BreedingOffspringBirthService(
            @Nonnull BreedingClaimLimitPolicyService limitPolicy
    ) {
        this.limitPolicy = limitPolicy;
    }

    void spawn(@Nonnull World world, @Nonnull BreedingPairContext context,
               @Nonnull BreedingLitterPlanner.Plan plan) {
        Store<EntityStore> store = resolveStore(world);
        ParentRefs parents = resolveParents(world, store, context);
        if (store == null || parents == null) {
            return;
        }
        SpawnSetup setup = resolveSetup(store, parents, context);
        if (setup == null) {
            return;
        }
        BreedingPairContext liveContext = refreshParentOwners(
                store, parents, context, setup
        );
        if (liveContext == null) {
            return;
        }
        BreedingFertilityOffspringService.FertilityRoll fertility =
                plan.fertility();
        if (fertility.offspringCount() <= 0) {
            logNoOffspring(liveContext, fertility);
            return;
        }
        BirthAllowance allowance = resolveAllowance(
                store, liveContext, setup, plan.resolvedRoles().size(),
                plan.resolvedRoles().getFirst()
        );
        int spawnedCount = spawnChildren(
                store, parents, liveContext, setup, allowance, plan.resolvedRoles()
        );
        finishLitter(store, parents, liveContext, fertility, spawnedCount);
    }

    @Nullable
    private BreedingPairContext refreshParentOwners(
            @Nonnull Store<EntityStore> store,
            @Nonnull ParentRefs parents,
            @Nonnull BreedingPairContext context,
            @Nonnull SpawnSetup setup
    ) {
        BreedingOffspringProgressionService.OwnerSnapshot parentA =
                BreedingOwnerSnapshotResolver.resolve(parents.parentA(), store);
        BreedingOffspringProgressionService.OwnerSnapshot parentB =
                BreedingOwnerSnapshotResolver.resolve(parents.parentB(), store);
        TwBreedingConfig.PairingSettings pairing = setup.config() == null
                ? null
                : setup.config().resolvePairing(firstNonBlank(
                        setup.parentARole(), setup.parentBRole()
                ));
        boolean requireSameOwner = pairing != null
                && pairing.isRequireSameOwner();
        if (!BreedingOwnerSnapshotResolver.allowsDelayedBirth(
                requireSameOwner, parentA, parentB
        )) {
            logInfo(String.format(
                    "Breeding delayed birth canceled after parent ownership changed: parentA=%s parentB=%s.",
                    context.parentAUuid(), context.parentBUuid()
            ));
            return null;
        }
        return context.withParentOwners(parentA, parentB);
    }

    @Nullable
    private SpawnSetup resolveSetup(
            @Nonnull Store<EntityStore> store,
            @Nonnull ParentRefs parents,
            @Nonnull BreedingPairContext context
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return null;
        }
        String parentARole = firstNonBlank(
                context.parentARoleId(), resolveRoleId(parents.parentA(), store)
        );
        String parentBRole = firstNonBlank(
                context.parentBRoleId(), resolveRoleId(parents.parentB(), store)
        );
        Vector3d position = resolveSpawnPosition(parents, store, context.spawnAnchor());
        if (position == null) {
            return null;
        }
        TwBreedingConfig config = resolveConfig(context.breedingConfigId());
        if (context.breedingConfigId() != null
                && !context.breedingConfigId().isBlank()
                && config == null) {
            logInfo(String.format(
                    "Breeding delayed birth canceled because config %s no longer resolves: parentA=%s parentB=%s.",
                    context.breedingConfigId(),
                    context.parentAUuid(),
                    context.parentBUuid()
            ));
            return null;
        }
        return new SpawnSetup(
                npcPlugin,
                config,
                parentARole,
                parentBRole,
                position,
                resolveRotation(parents, store)
        );
    }

    @Nonnull
    private BirthAllowance resolveAllowance(
            @Nonnull Store<EntityStore> store,
            @Nonnull BreedingPairContext context,
            @Nonnull SpawnSetup setup,
            int requested,
            @Nonnull BreedingResolvedSpawnRole firstRole
    ) {
        BreedingClaimLimitPolicyService.Decision decision = limitPolicy.evaluate(
                store,
                setup.position(),
                setup.config(),
                firstRole.roleId(),
                context.parentAOwner().ownerId(),
                context.parentBOwner().ownerId()
        );
        if (!decision.allowed()) {
            logBlocked(context, decision.reason());
            return BirthAllowance.none();
        }
        int count = decision.capEnforced()
                ? Math.min(requested, decision.remainingHeadroom())
                : requested;
        count = limitByNearbyPopulation(store, setup, firstRole, count);
        return new BirthAllowance(Math.max(0, count), firstRole);
    }

    private int limitByNearbyPopulation(
            @Nonnull Store<EntityStore> store,
            @Nonnull SpawnSetup setup,
            @Nonnull BreedingResolvedSpawnRole firstRole,
            int requested
    ) {
        TwBreedingConfig config = setup.config();
        String pairingRole = firstNonBlank(
                setup.parentARole(), firstRole.roleId()
        );
        TwBreedingConfig.PairingSettings pairing = config == null
                ? null
                : config.resolvePairing(pairingRole);
        if (pairing == null) {
            return requested;
        }
        int maxNearby = pairing.resolveMaxNearbySameType(pairingRole);
        String typeKey = populationTypeService.resolveTypeKey(
                firstRole.roleId(), config
        );
        if (maxNearby <= 0 || typeKey == null || typeKey.isBlank()) {
            return requested;
        }
        double radius = sanitizeRadius(pairing.getBreedRadius());
        int existing = populationTypeService.countNearbyOfType(
                store, setup.position(), radius, config, typeKey
        );
        int allowed = nearbyAllowance.limit(requested, existing, maxNearby);
        if (allowed < requested) {
            logInfo(String.format(
                    "Breeding litter limited by nearby population: type=%s existing=%d max=%d requested=%d allowed=%d.",
                    typeKey, existing, maxNearby, requested, allowed
            ));
        }
        return allowed;
    }

    private int spawnChildren(
            @Nonnull Store<EntityStore> store,
            @Nonnull ParentRefs parents,
            @Nonnull BreedingPairContext context,
            @Nonnull SpawnSetup setup,
            @Nonnull BirthAllowance allowance,
            @Nonnull List<BreedingResolvedSpawnRole> roles
    ) {
        int spawned = 0;
        for (int index = 0; index < allowance.count(); index++) {
            BreedingResolvedSpawnRole role = roles.get(index);
            if (role == null) {
                continue;
            }
            Vector3d attempt = offsetPosition(setup.position(), index);
            Pair<Ref<EntityStore>, NPCEntity> child = spawnService.spawnWithFallback(
                    setup.npcPlugin(), store, role.roleIndex(), attempt, setup.rotation()
            );
            if (child == null || child.first() == null || child.second() == null) {
                logSpawnFailure(context, role, attempt);
                continue;
            }
            if (finishChild(store, parents, context, setup.config(), role, child)) {
                spawned++;
            }
        }
        return spawned;
    }

    private boolean finishChild(
            @Nonnull Store<EntityStore> store,
            @Nonnull ParentRefs parents,
            @Nonnull BreedingPairContext context,
            @Nullable TwBreedingConfig config,
            @Nonnull BreedingResolvedSpawnRole role,
            @Nonnull Pair<Ref<EntityStore>, NPCEntity> child
    ) {
        NPCEntity childNpc = child.second();
        if (childNpc.getUuid() == null) {
            childNpc.setToDespawn();
            return false;
        }
        return completionGuard.complete(
                () -> postSpawnService.finish(new BreedingOffspringPostSpawnService.Request(
                        child.first(),
                        childNpc,
                        parents.parentA(),
                        parents.parentB(),
                        store,
                        config,
                        role.roleId(),
                        childNpc.getUuid(),
                        context.breedingConfigId(),
                        context.parentAOwner(),
                        context.parentBOwner(),
                        context.parentATamed(),
                        context.parentBTamed(),
                        role.adultRoleId(),
                        role.gender(),
                        role.lifecycleFamily(),
                        CompanionLifeStageService.LifecycleFamilyResolution.PLANNED_SELECTION_ONLY,
                        cooldown -> logChildCooldown(childNpc, cooldown),
                        physics -> logSpawnSuccess(context, childNpc, role, physics)
                )),
                failure -> logWarn("Breeding offspring initialization failed.", failure)
        );
    }

    private void finishLitter(
            @Nonnull Store<EntityStore> store,
            @Nonnull ParentRefs parents,
            @Nonnull BreedingPairContext context,
            @Nonnull BreedingFertilityOffspringService.FertilityRoll fertility,
            int spawnedCount
    ) {
        if (spawnedCount > 0) {
            CompanionLevelingService.awardBreedingXp(parents.parentA(), store);
            CompanionLevelingService.awardBreedingXp(parents.parentB(), store);
        }
        if (spawnedCount > 1) {
            logInfo(String.format(
                    "Breeding produced multiple offspring: count=%d parentA=%s parentB=%s expected=%.2f.",
                    spawnedCount, context.parentAUuid(), context.parentBUuid(),
                    fertility.expectedOffspring()
            ));
        }
    }

    @Nullable
    private static ParentRefs resolveParents(
            @Nonnull World world,
            @Nullable Store<EntityStore> store,
            @Nonnull BreedingPairContext context
    ) {
        if (store == null) {
            return null;
        }
        Ref<EntityStore> parentA = world.getEntityRef(context.parentAUuid());
        Ref<EntityStore> parentB = world.getEntityRef(context.parentBUuid());
        if (!isLiveNpc(parentA, store) || !isLiveNpc(parentB, store)) {
            return null;
        }
        return new ParentRefs(parentA, parentB);
    }

    private static boolean isLiveNpc(
            @Nullable Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        return ref != null
                && ref.isValid()
                && store.getComponent(ref, NPCEntity.getComponentType()) != null;
    }

    @Nullable
    private static Store<EntityStore> resolveStore(@Nonnull World world) {
        return world.getEntityStore() == null ? null : world.getEntityStore().getStore();
    }

    @Nullable
    private static String resolveRoleId(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
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
    private static Vector3d resolveSpawnPosition(
            @Nonnull ParentRefs parents,
            @Nonnull Store<EntityStore> store,
            @Nullable Vector3d fallback
    ) {
        TransformComponent a = transform(parents.parentA(), store);
        TransformComponent b = transform(parents.parentB(), store);
        if (a != null && b != null) {
            return new Vector3d(
                    (a.getPosition().x + b.getPosition().x) * 0.5,
                    Math.max(a.getPosition().y, b.getPosition().y) + SPAWN_HEIGHT_OFFSET,
                    (a.getPosition().z + b.getPosition().z) * 0.5
            );
        }
        return fallback == null ? null : new Vector3d(fallback);
    }

    @Nonnull
    private static Rotation3f resolveRotation(
            @Nonnull ParentRefs parents,
            @Nonnull Store<EntityStore> store
    ) {
        TransformComponent a = transform(parents.parentA(), store);
        TransformComponent b = transform(parents.parentB(), store);
        if (a != null && b != null) {
            Vector3d delta = new Vector3d(b.getPosition()).sub(a.getPosition());
            if (delta.lengthSquared() > 0.00001) {
                return TameworkRotationUtil.lookAt(delta);
            }
        }
        TransformComponent fallback = a != null ? a : b;
        return fallback == null ? new Rotation3f() : new Rotation3f(fallback.getRotation());
    }

    @Nullable
    private static TransformComponent transform(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        return store.getComponent(ref, TransformComponent.getComponentType());
    }

    @Nullable
    private static TwBreedingConfig resolveConfig(@Nullable String configId) {
        return configId == null || configId.isBlank()
                ? null
                : TwBreedingConfig.resolveById(configId);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        return first != null && !first.isBlank()
                ? first
                : second != null && !second.isBlank() ? second : null;
    }

    private static double sanitizeRadius(double radius) {
        return !Double.isFinite(radius) || radius <= 0.0 ? 10.0 : radius;
    }

    @Nonnull
    private static Vector3d offsetPosition(@Nonnull Vector3d base, int index) {
        if (index == 0) {
            return new Vector3d(base);
        }
        return new Vector3d(
                base.x + ThreadLocalRandom.current().nextDouble(-0.55, 0.55),
                base.y,
                base.z + ThreadLocalRandom.current().nextDouble(-0.55, 0.55)
        );
    }

    private static void logNoOffspring(
            BreedingPairContext context,
            BreedingFertilityOffspringService.FertilityRoll fertility
    ) {
        logInfo(String.format(
                "Breeding produced no offspring: parentA=%s parentB=%s expected=%.2f.",
                context.parentAUuid(), context.parentBUuid(), fertility.expectedOffspring()
        ));
    }

    private static void logBlocked(BreedingPairContext context, String reason) {
        logInfo(String.format(
                "Breeding spawn blocked by population limit: parentA=%s parentB=%s reason=%s.",
                context.parentAUuid(), context.parentBUuid(), reason
        ));
    }

    private static void logSpawnFailure(
            BreedingPairContext context,
            BreedingResolvedSpawnRole role,
            Vector3d position
    ) {
        logWarn(String.format(
                "Breeding spawn failed: role=%s parentA=%s parentB=%s pos=(%.2f, %.2f, %.2f).",
                role.roleId(), context.parentAUuid(), context.parentBUuid(),
                position.x, position.y, position.z
        ), null);
    }

    private static void logSpawnSuccess(
            BreedingPairContext context,
            NPCEntity child,
            BreedingResolvedSpawnRole role,
            String physics
    ) {
        logInfo(String.format(
                "Breeding spawn success: child=%s role=%s parentA=%s parentB=%s spawnPhysicsReset={%s}.",
                child.getUuid(), role.roleId(), context.parentAUuid(), context.parentBUuid(), physics
        ));
    }

    private static void logChildCooldown(
            NPCEntity child,
            BreedingCooldownService.Resolution cooldown
    ) {
        logInfo(String.format(
                "Breeding child cooldown applied: npc=%s basis=%s gameMs=%d.",
                child.getUuid(), cooldown.basis(), cooldown.durationMs()
        ));
    }

    private static void logInfo(String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null
                && plugin.isDebugBreedingEnabled()
                && message != null && !message.isBlank()) {
            plugin.getLogger().at(Level.INFO).log(message);
        }
    }

    private static void logWarn(String message, @Nullable Throwable failure) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null
                || message == null || message.isBlank()) {
            return;
        }
        if (failure == null) {
            plugin.getLogger().at(Level.WARNING).log(message);
        } else {
            plugin.getLogger().at(Level.WARNING).withCause(failure).log(message);
        }
    }

    private record ParentRefs(Ref<EntityStore> parentA, Ref<EntityStore> parentB) {
    }

    private record SpawnSetup(
            NPCPlugin npcPlugin,
            @Nullable TwBreedingConfig config,
            @Nullable String parentARole,
            @Nullable String parentBRole,
            Vector3d position,
            Rotation3f rotation
    ) {
    }

    private record BirthAllowance(
            int count,
            @Nullable BreedingResolvedSpawnRole firstRole
    ) {
        @Nonnull
        static BirthAllowance none() {
            return new BirthAllowance(0, null);
        }
    }
}
