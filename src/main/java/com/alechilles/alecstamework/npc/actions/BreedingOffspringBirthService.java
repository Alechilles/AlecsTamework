package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Resolves and spawns a litter from current world state after the pairing animation.
 *
 * <p>Capacity is checked once against the released SimpleClaims scan and current
 * world store. The resulting headroom bounds this litter without creating durable
 * leases.
 */
final class BreedingOffspringBirthService {
    private static final double SPAWN_HEIGHT_OFFSET = 1.0;

    private final BreedingFertilityOffspringService fertilityService =
            new BreedingFertilityOffspringService();
    private final BreedingOffspringSpawnService spawnService =
            new BreedingOffspringSpawnService(new BreedingOffspringRoleResolver());
    private final BreedingClaimLimitPolicyService limitPolicy =
            new BreedingClaimLimitPolicyService();
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

    void spawn(@Nonnull World world, @Nonnull BreedingPairContext context) {
        Store<EntityStore> store = resolveStore(world);
        ParentRefs parents = resolveParents(world, store, context);
        if (store == null || parents == null) {
            return;
        }
        BreedingFertilityOffspringService.FertilityRoll fertility =
                fertilityService.rollOffspring(parents.parentA(), parents.parentB(), store);
        if (fertility.offspringCount() <= 0) {
            logNoOffspring(context, fertility);
            return;
        }
        SpawnSetup setup = resolveSetup(store, parents, context);
        if (setup == null) {
            return;
        }
        BirthAllowance allowance = resolveAllowance(
                store, context, setup, fertility.offspringCount()
        );
        int spawnedCount = spawnChildren(store, parents, context, setup, allowance);
        finishLitter(store, parents, context, fertility, spawnedCount);
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
        return new SpawnSetup(
                npcPlugin,
                resolveConfig(context.breedingConfigId()),
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
            int requested
    ) {
        BreedingResolvedSpawnRole firstRole = resolveRole(setup, context);
        if (firstRole == null) {
            return BirthAllowance.none();
        }
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
        return new BirthAllowance(Math.max(0, count), firstRole);
    }

    private int spawnChildren(
            @Nonnull Store<EntityStore> store,
            @Nonnull ParentRefs parents,
            @Nonnull BreedingPairContext context,
            @Nonnull SpawnSetup setup,
            @Nonnull BirthAllowance allowance
    ) {
        int spawned = 0;
        for (int index = 0; index < allowance.count(); index++) {
            BreedingResolvedSpawnRole role = index == 0
                    ? allowance.firstRole()
                    : resolveRole(setup, context);
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

    @Nullable
    private BreedingResolvedSpawnRole resolveRole(
            @Nonnull SpawnSetup setup,
            @Nonnull BreedingPairContext context
    ) {
        return spawnService.resolveSpawnRole(
                setup.parentARole(),
                setup.parentBRole(),
                setup.config(),
                context.parentARoleIndex(),
                context.parentBRoleIndex(),
                setup.npcPlugin(),
                Math.random(),
                Math.random()
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
                return Rotation3f.lookAt(delta);
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
