package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService;
import com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator;
import com.alechilles.alecstamework.npc.breeding.BreedingPreparedPopulationRegistry;
import com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.npc.breeding.TameworkBreedingServices;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Resolves fresh Hytale parent state and spawns only preplanned children for a claimed job. */
final class BreedingHytaleJobRuntime implements BreedingJobExecutionService.Runtime<BreedingHytaleJobRuntime.Context> {
    private static final String HEARTS_PARTICLE = "Hearts";

    private final BreedingParentStateService parentStateService = new BreedingParentStateService();
    private final BreedingCapacityRequestFactory capacityFactory = new BreedingCapacityRequestFactory();
    private final BreedingParticleOffsetResolver particleOffsetResolver = new BreedingParticleOffsetResolver();
    private final BreedingCooldownRollbackService rollbackService = new BreedingCooldownRollbackService();
    private final BreedingPreparedPopulationRegistry preparedPopulation;
    private final BreedingPreparedChildSpawnService childSpawnService;

    BreedingHytaleJobRuntime() {
        this(TameworkBreedingServices.shared());
    }

    BreedingHytaleJobRuntime(@Nonnull TameworkBreedingServices services) {
        this.preparedPopulation = services.preparedPopulationRegistry();
        BreedingOffspringPostSpawnService postSpawnService = new BreedingOffspringPostSpawnService(
                new BreedingOffspringProgressionService(),
                new BreedingCooldownService(),
                new BreedingPairingEffectsService(particleOffsetResolver)
        );
        this.childSpawnService = new BreedingPreparedChildSpawnService(
                preparedPopulation,
                new BreedingOffspringSpawnService(new BreedingOffspringRoleResolver()),
                postSpawnService
        );
    }

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
        return spawnChildResult(job, child, childIndex, context)
                == BreedingJobExecutionService.ChildSpawnResult.SPAWNED;
    }

    @Override
    @Nonnull
    public BreedingJobExecutionService.ChildSpawnResult spawnChildResult(
            @Nonnull BreedingBirthJob job,
            @Nonnull PlannedChild child,
            int childIndex,
            @Nonnull Context context) {
        return childSpawnService.spawn(job, child, childIndex, context);
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
    public void onAdmissionShrunk(
            @Nonnull BreedingBirthJob original,
            @Nonnull java.util.List<PlannedChild> retainedChildren,
            @Nonnull Context context) {
        java.util.List<PlannedChild> source = original.admittedChildren();
        java.util.List<String> retainedKeys = new java.util.ArrayList<>(
                retainedChildren.size()
        );
        int retainedIndex = 0;
        for (int sourceIndex = 0;
             sourceIndex < source.size() && retainedIndex < retainedChildren.size();
             sourceIndex++) {
            if (!source.get(sourceIndex).equals(retainedChildren.get(retainedIndex))) {
                continue;
            }
            PreparedBreedingPopulationBatch.ReservedChild reserved =
                    preparedPopulation.child(original.jobId(), sourceIndex).orElse(null);
            if (reserved == null) {
                throw new IllegalStateException("Prepared breeding child mapping is incomplete");
            }
            retainedKeys.add(reserved.childKey());
            retainedIndex++;
        }
        if (retainedIndex != retainedChildren.size()) {
            throw new IllegalStateException("Spawn-time admission no longer matches prepared litter");
        }
        preparedPopulation.retainOnly(
                original.jobId(), retainedKeys, "breeding-spawn-capacity-shrunk"
        );
    }

    @Override
    public void cancelPopulation(@Nonnull BreedingBirthJob job, @Nonnull String reason) {
        preparedPopulation.cancelRemaining(job.jobId(), reason);
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

    private void logInfo(String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null && plugin.isDebugBreedingEnabled()) {
            plugin.getLogger().at(Level.INFO).log(message);
        }
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
