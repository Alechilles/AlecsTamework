package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.CompanionReviveEligibilityService;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.systems.CommandNpcRelocationOnLoadSystem;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Set;
import javax.annotation.Nonnull;

/** Reconciles owned companion entity load/unload/removal without mutating ECS state. */
public final class CompanionPopulationLifecycleSystem extends RefSystem<EntityStore> {
    private final CompanionPopulationRuntimeReconciler reconciler;
    private final OwnerPopulationIndex ownerIndex;
    private final CompanionIdentityResolver identityResolver;
    private final CompanionRemovalLifecycleClassifier removalClassifier;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, CommandNpcRelocationOnLoadSystem.class)
    );

    public CompanionPopulationLifecycleSystem(
            @Nonnull CompanionPopulationRuntimeReconciler reconciler,
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull CompanionRemovalLifecycleClassifier removalClassifier,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType
    ) {
        this.reconciler = reconciler;
        this.ownerIndex = ownerIndex;
        this.identityResolver = identityResolver;
        this.removalClassifier = removalClassifier;
        this.ownerType = ownerType;
        this.uuidType = uuidType;
        this.transformType = transformType;
        this.query = Query.and(npcType, uuidType, transformType);
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        CompanionPopulationEntityObservation observation = CompanionPopulationEntityObservation.fromStore(
                ref, store, ownerType, uuidType, transformType, null
        );
        if (observation != null && observation.ownerUuid() != null) {
            reconciler.observePhysical(
                    observation.npcUuid(), observation.ownerUuid(), observation.worldName(),
                    observation.chunkX(), observation.chunkZ(), CompanionLifecycleState.ACTIVE,
                    "ecs-entity-" + reason.name().toLowerCase(java.util.Locale.ROOT)
            );
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        CompanionPopulationEntityObservation observation = CompanionPopulationEntityObservation.fromStore(
                ref, store, ownerType, uuidType, transformType, null
        );
        if (observation == null) {
            return;
        }
        String profileId = identityResolver.resolveProfileId(observation.npcUuid()).orElse(null);
        if (profileId == null && observation.ownerUuid() == null) {
            return;
        }
        CompanionLifecycleState current = profileId == null
                ? CompanionLifecycleState.ACTIVE
                : ownerIndex.entry(profileId)
                .map(com.alechilles.alecstamework.ownership.OwnerPopulationEntry::lifecycleState)
                .orElse(CompanionLifecycleState.ACTIVE);
        CompanionLifecycleState classified = removalClassifier.classify(
                observation.npcUuid(), reason, current,
                isPermanentDeath(ref, store, observation.npcUuid())
        );
        CompanionReviveEligibilityService.Eligibility eligibility =
                CompanionReviveEligibilityService.current().findByNpc(observation.npcUuid());
        if (classified == CompanionLifecycleState.UNKNOWN_DORMANT
                && eligibility != null
                && eligibility.authority()
                == CompanionReviveEligibilityService.Authority.BONDED_VESSEL) {
            classified = CompanionLifecycleState.CAPTURED;
        }
        if (classified == CompanionLifecycleState.UNLOADED) {
            reconciler.observePhysical(
                    observation.npcUuid(), observation.ownerUuid(), observation.worldName(),
                    observation.chunkX(), observation.chunkZ(), classified, "ecs-entity-unload"
            );
        } else {
            java.util.UUID dormantOwner = classified == CompanionLifecycleState.RELEASED
                    ? null
                    : observation.ownerUuid();
            reconciler.observeDormant(
                    observation.npcUuid(), dormantOwner, observation.worldName(),
                    classified, "ecs-entity-remove"
            );
        }
    }

    private static boolean isPermanentDeath(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull Store<EntityStore> store,
                                            @Nonnull java.util.UUID npcUuid) {
        try {
            if (!store.getArchetype(ref).contains(DeathComponent.getComponentType())) {
                return false;
            }
            ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                    TameworkCommandLinksComponent.getComponentType();
            TameworkCommandLinksComponent links = linksType == null
                    ? null
                    : store.getComponent(ref, linksType);
            boolean commandLinked = links != null && links.getToolIds() != null
                    && links.getToolIds().length > 0;
            return !commandLinked
                    && !CompanionReviveEligibilityService.current()
                    .protectsFromPermanentDeath(npcUuid);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }
}
