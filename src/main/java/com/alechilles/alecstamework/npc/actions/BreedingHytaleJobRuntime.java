package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.damage.RecentSpawnProtectionService;
import com.alechilles.alecstamework.items.CommandCompanionSpawnPhysicsResetService;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService;
import com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator;
import com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Resolves fresh Hytale parent state and spawns only preplanned children for a claimed job. */
final class BreedingHytaleJobRuntime implements BreedingJobExecutionService.Runtime<BreedingHytaleJobRuntime.Context> {
    private static final String HEARTS_PARTICLE = "Hearts";

    private final BreedingParentStateService parentStateService = new BreedingParentStateService();
    private final BreedingCapacityRequestFactory capacityFactory = new BreedingCapacityRequestFactory();
    private final BreedingOffspringSpawnService spawnService =
            new BreedingOffspringSpawnService(new BreedingOffspringRoleResolver());
    private final BreedingOffspringProgressionService progressionService = new BreedingOffspringProgressionService();
    private final BreedingParticleOffsetResolver particleOffsetResolver = new BreedingParticleOffsetResolver();
    private final BreedingParentCooldownResolver cooldownResolver = new BreedingParentCooldownResolver();
    private final BreedingCooldownRollbackService rollbackService = new BreedingCooldownRollbackService();
    private final BreedingSpawnCompletionGuard spawnCompletionGuard =
            new BreedingSpawnCompletionGuard();

    @Override
    @Nonnull
    public BreedingJobExecutionService.ParentResolution<Context> resolveParents(
            @Nonnull BreedingBirthJob job) {
        Universe universe = Universe.get();
        World world = universe != null ? universe.getWorld(job.pairKey().worldId()) : null;
        Store<EntityStore> store = world != null && world.getEntityStore() != null
                ? world.getEntityStore().getStore()
                : null;
        if (world == null || store == null) {
            return BreedingJobExecutionService.ParentResolution.invalid("world-unloaded");
        }
        Ref<EntityStore> firstRef = world.getEntityRef(job.firstParent().entityUuid());
        Ref<EntityStore> secondRef = world.getEntityRef(job.secondParent().entityUuid());
        if (!valid(firstRef) || !valid(secondRef)) {
            return BreedingJobExecutionService.ParentResolution.invalid("parent-missing");
        }
        NPCEntity firstNpc = store.getComponent(firstRef, NPCEntity.getComponentType());
        NPCEntity secondNpc = store.getComponent(secondRef, NPCEntity.getComponentType());
        TameworkBreedingComponent firstBreeding = breeding(firstRef, store);
        TameworkBreedingComponent secondBreeding = breeding(secondRef, store);
        if (firstNpc == null || secondNpc == null || firstBreeding == null || secondBreeding == null
                || isDead(firstRef, store) || isDead(secondRef, store)) {
            return BreedingJobExecutionService.ParentResolution.invalid("parent-dead-or-component-missing");
        }
        if (!parentStateService.matchesIdentity(job.firstParent(), firstRef, firstNpc, store)
                || !parentStateService.matchesIdentity(job.secondParent(), secondRef, secondNpc, store)) {
            return BreedingJobExecutionService.ParentResolution.invalid("parent-identity-mismatch");
        }
        Context context = context(
                world,
                store,
                firstRef,
                secondRef,
                firstNpc,
                secondNpc,
                firstBreeding,
                secondBreeding
        );
        return BreedingJobExecutionService.ParentResolution.valid(store, context);
    }

    @Override
    public void showHearts(@Nonnull BreedingBirthJob job, @Nonnull Context context) {
        spawnHearts(context.firstRef(), context.firstNpc(), context.store());
        spawnHearts(context.secondRef(), context.secondNpc(), context.store());
    }

    @Override
    public BreedingPopulationAdmissionService.AdmissionRequest buildSpawnAdmissionRequest(
            @Nonnull BreedingBirthJob job,
            @Nonnull Context context) {
        BreedingPairingCoordinator.CapacityDecision decision = capacityFactory.prepare(
                job.jobId(),
                job.mode(),
                job.plan(),
                job.anchor(),
                context.store(),
                context.config(),
                context.firstRoleId(),
                context.firstOwner(),
                context.secondOwner(),
                job.reservation().scope()
        );
        return decision.allowed() ? decision.request() : null;
    }

    @Override
    public boolean spawnChild(@Nonnull BreedingBirthJob job,
                              @Nonnull PlannedChild child,
                              int childIndex,
                              @Nonnull Context context) {
        NPCPlugin plugin = NPCPlugin.get();
        int roleIndex = plugin != null ? plugin.getIndex(child.roleId()) : -1;
        if (plugin == null || roleIndex < 0) {
            return false;
        }
        Vector3d spawnPosition = spawnPosition(job, childIndex);
        Pair<Ref<EntityStore>, NPCEntity> spawned = spawnService.spawnWithFallback(
                plugin,
                context.store(),
                roleIndex,
                spawnPosition,
                spawnRotation(context)
        );
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            return false;
        }
        Ref<EntityStore> childRef = spawned.first();
        NPCEntity childNpc = spawned.second();
        return spawnCompletionGuard.complete(
                () -> initializeSpawnedChild(job, child, childRef, childNpc, context),
                exception -> logSpawnFollowUpFailure(job, childNpc, exception));
    }

    private void initializeSpawnedChild(BreedingBirthJob job,
                                        PlannedChild child,
                                        Ref<EntityStore> childRef,
                                        NPCEntity childNpc,
                                        Context context) {
        String physicsReset = CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics(
                childRef,
                childNpc,
                context.store()
        );
        RecentSpawnProtectionService.getInstance().record(
                childNpc.getUuid(),
                "breeding_offspring",
                child.roleId(),
                System.currentTimeMillis()
        );
        BreedingParentCooldownResolver.ResolvedCooldown childCooldown = cooldownResolver.resolve(
                context.config(),
                childRef,
                context.store()
        );
        progressionService.applyOffspringState(
                childRef,
                childNpc,
                context.firstRef(),
                context.secondRef(),
                child.roleId(),
                context.firstOwner(),
                context.secondOwner(),
                context.firstTamed(),
                context.secondTamed(),
                configId(context.config()),
                childCooldown.durationMs(),
                child.adultRoleId(),
                TwBreedingConfig.Gender.fromConfigValue(child.gender()),
                lifecycleFamily(context.config(), child),
                context.store()
        );
        spawnHearts(childRef, childNpc, context.store());
        logInfo("Breeding spawn success: child=" + childNpc.getUuid()
                + " role=" + child.roleId() + " physicsReset={" + physicsReset + "}.");
    }

    @Override
    public void onCompleted(@Nonnull BreedingBirthJob job,
                            int spawnedChildren,
                            @Nonnull Context context) {
        if (spawnedChildren > 0) {
            CompanionLevelingService.awardBreedingXp(context.firstRef(), context.store());
            CompanionLevelingService.awardBreedingXp(context.secondRef(), context.store());
        }
        if (spawnedChildren > 1) {
            logInfo("Breeding produced multiple offspring: job=" + job.jobId()
                    + " count=" + spawnedChildren + ".");
        }
    }

    @Override
    public void rollbackProvisionalCooldown(@Nonnull BreedingBirthJob job) {
        rollbackService.rollback(job);
    }

    private Context context(World world,
                            Store<EntityStore> store,
                            Ref<EntityStore> firstRef,
                            Ref<EntityStore> secondRef,
                            NPCEntity firstNpc,
                            NPCEntity secondNpc,
                            TameworkBreedingComponent firstBreeding,
                            TameworkBreedingComponent secondBreeding) {
        String firstRole = roleId(firstNpc);
        TwBreedingConfig config = BreedingConfigResolver.resolveConfig(firstRef, store, firstBreeding);
        return new Context(
                world,
                store,
                firstRef,
                secondRef,
                firstNpc,
                secondNpc,
                firstBreeding,
                secondBreeding,
                firstRole,
                roleId(secondNpc),
                owner(firstRef, store),
                owner(secondRef, store),
                TamedStateResolver.isTamed(firstRef, store),
                TamedStateResolver.isTamed(secondRef, store),
                config
        );
    }

    private Vector3d spawnPosition(BreedingBirthJob job, int childIndex) {
        double offsetX = childIndex == 0 ? 0.0 : ThreadLocalRandom.current().nextDouble(-0.55, 0.55);
        double offsetZ = childIndex == 0 ? 0.0 : ThreadLocalRandom.current().nextDouble(-0.55, 0.55);
        return new Vector3d(job.anchor().x() + offsetX, job.anchor().y(), job.anchor().z() + offsetZ);
    }

    private Rotation3f spawnRotation(Context context) {
        TransformComponent first = transform(context.firstRef(), context.store());
        TransformComponent second = transform(context.secondRef(), context.store());
        if (first != null && second != null) {
            Vector3d delta = new Vector3d(second.getPosition()).sub(first.getPosition());
            if (delta.lengthSquared() > 0.00001) {
                return Rotation3f.lookAt(delta);
            }
        }
        TransformComponent fallback = first != null ? first : second;
        return fallback != null ? new Rotation3f(fallback.getRotation()) : new Rotation3f();
    }

    @Nullable
    private TwBreedingConfig.RoleFamily lifecycleFamily(@Nullable TwBreedingConfig config,
                                                        PlannedChild child) {
        if (config == null) {
            return null;
        }
        TwBreedingConfig.RoleFamily family = config.resolveLifecycleLineFamilyForRole(child.adultRoleId());
        if (family == null) {
            family = config.resolveLifecycleLineFamilyForRole(child.roleId());
        }
        return family != null ? family : config.resolveLifecycleFamilyForRole(child.adultRoleId());
    }

    private void spawnHearts(Ref<EntityStore> ref, NPCEntity npc, Store<EntityStore> store) {
        TransformComponent transform = transform(ref, store);
        if (transform == null) {
            return;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        Vector3d offset = particleOffsetResolver.resolveOffset(npc);
        position.add(offset);
        ParticleUtil.spawnParticleEffect(HEARTS_PARTICLE, position, store);
    }

    private boolean isDead(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, DeathComponent> type = DeathComponent.getComponentType();
        return type != null && store.getComponent(ref, type) != null;
    }

    private TameworkBreedingComponent breeding(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        return type != null ? store.getComponent(ref, type) : null;
    }

    private TransformComponent transform(Ref<EntityStore> ref, Store<EntityStore> store) {
        return valid(ref) ? store.getComponent(ref, TransformComponent.getComponentType()) : null;
    }

    private boolean valid(@Nullable Ref<EntityStore> ref) {
        return ref != null && ref.isValid();
    }

    @Nonnull
    private BreedingOffspringProgressionService.OwnerSnapshot owner(
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType != null ? store.getComponent(ref, ownerType) : null;
        UUID ownerId = owner != null ? owner.getOwnerId() : null;
        if (ownerId == null) {
            ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                    TameworkCommandLinksComponent.getComponentType();
            TameworkCommandLinksComponent links = linksType != null ? store.getComponent(ref, linksType) : null;
            ownerId = links != null ? links.getOwnerId() : null;
        }
        return ownerId == null
                ? BreedingOffspringProgressionService.OwnerSnapshot.empty()
                : new BreedingOffspringProgressionService.OwnerSnapshot(
                        ownerId,
                        owner != null ? owner.getOwnerName() : null
                );
    }

    @Nullable
    private String roleId(NPCEntity npc) {
        if (npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
            return npc.getRoleName();
        }
        NPCPlugin plugin = NPCPlugin.get();
        return plugin != null && npc.getRoleIndex() >= 0 ? plugin.getName(npc.getRoleIndex()) : null;
    }

    @Nullable
    private String configId(@Nullable TwBreedingConfig config) {
        return config != null ? config.getId() : null;
    }

    private void logInfo(String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null && plugin.isDebugBreedingEnabled()) {
            plugin.getLogger().at(Level.INFO).log(message);
        }
    }

    private void logSpawnFollowUpFailure(BreedingBirthJob job,
                                         NPCEntity childNpc,
                                         RuntimeException failure) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null) {
            return;
        }
        plugin.getLogger().at(Level.WARNING).withCause(failure).log(
                "Breeding child was created but follow-up initialization failed: job="
                        + job.jobId() + " child=" + childNpc.getUuid()
                        + ". Birth remains committed to prevent a duplicate litter.");
    }

    record Context(
            World world,
            Store<EntityStore> store,
            Ref<EntityStore> firstRef,
            Ref<EntityStore> secondRef,
            NPCEntity firstNpc,
            NPCEntity secondNpc,
            TameworkBreedingComponent firstBreeding,
            TameworkBreedingComponent secondBreeding,
            @Nullable String firstRoleId,
            @Nullable String secondRoleId,
            BreedingOffspringProgressionService.OwnerSnapshot firstOwner,
            BreedingOffspringProgressionService.OwnerSnapshot secondOwner,
            boolean firstTamed,
            boolean secondTamed,
            @Nullable TwBreedingConfig config) {
    }
}
