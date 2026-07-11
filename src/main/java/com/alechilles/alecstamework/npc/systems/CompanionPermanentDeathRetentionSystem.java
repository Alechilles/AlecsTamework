package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionPermanentDeathCoordinator;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps crash-restored direct-death corpses alive until permanent release is durable, then lets
 * vanilla corpse removal resume. This covers DeathComponent producers outside the damage event.
 */
public final class CompanionPermanentDeathRetentionSystem extends EntityTickingSystem<EntityStore> {
    private static final long RETRY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private final CompanionPermanentDeathCoordinator coordinator;
    private final OwnerPopulationIndex ownerIndex;
    private final CompanionIdentityResolver identityResolver;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, DeathComponent> deathType;
    private final ComponentType<EntityStore, DeferredCorpseRemoval> corpseRemovalType;
    private final Query<EntityStore> query;
    private final ConcurrentHashMap<UUID, Long> retryAfterByNpc = new ConcurrentHashMap<>();
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, DeathSystems.TickCorpseRemoval.class),
            new SystemDependency<>(Order.BEFORE, DeathSystems.CorpseRemoval.class)
    );

    public CompanionPermanentDeathRetentionSystem(
            @Nonnull CompanionPermanentDeathCoordinator coordinator,
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.linksType = TameworkCommandLinksComponent.getComponentType();
        this.uuidType = Objects.requireNonNull(UUIDComponent.getComponentType(), "uuidType");
        this.deathType = Objects.requireNonNull(DeathComponent.getComponentType(), "deathType");
        this.corpseRemovalType = Objects.requireNonNull(
                DeferredCorpseRemoval.getComponentType(), "corpseRemovalType"
        );
        this.query = Query.and(NPCEntity.getComponentType(), uuidType, deathType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UUIDComponent identity = chunk.getComponent(index, uuidType);
        DeathComponent death = chunk.getComponent(index, deathType);
        if (identity == null || identity.getUuid() == null || death == null) {
            return;
        }
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        UUID npcUuid = identity.getUuid();
        TameworkOwnerComponent owner = store.getComponent(ref, ownerType);
        if (owner != null && owner.getOwnerId() != null) {
            retainOwnedPermanentDeath(ref, store, commandBuffer, npcUuid, owner.getOwnerId(), death);
            return;
        }
        retryAfterByNpc.remove(npcUuid);
        if (isDurablyReleased(npcUuid)) {
            resumeCorpseRemoval(ref, store, commandBuffer);
        }
    }

    private void retainOwnedPermanentDeath(@Nonnull Ref<EntityStore> ref,
                                           @Nonnull Store<EntityStore> store,
                                           @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                           @Nonnull UUID npcUuid,
                                           @Nonnull UUID ownerUuid,
                                           @Nonnull DeathComponent death) {
        TameworkCommandLinksComponent links = linksType == null
                ? null : store.getComponent(ref, linksType);
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        if (CompanionRevivePolicy.supportsRevive(roleId, links)) {
            retryAfterByNpc.remove(npcUuid);
            return;
        }
        commandBuffer.putComponent(
                ref,
                corpseRemovalType,
                CompanionPermanentDeathHold.create(deathParticles(ref, store))
        );
        long now = System.nanoTime();
        Long retryAfter = retryAfterByNpc.get(npcUuid);
        if (coordinator.isPending(npcUuid) || (retryAfter != null && now < retryAfter)) {
            return;
        }
        retryAfterByNpc.put(npcUuid, saturatingAdd(now, RETRY_INTERVAL_NANOS));
        coordinator.interceptExistingDeath(ref, store, npcUuid, ownerUuid, death);
    }

    private boolean isDurablyReleased(@Nonnull UUID npcUuid) {
        String profileId = identityResolver.resolveProfileId(npcUuid).orElse(null);
        if (profileId == null) {
            return false;
        }
        OwnerPopulationEntry entry = ownerIndex.entry(profileId).orElse(null);
        return entry != null
                && entry.ownerId() == null
                && entry.lifecycleState() == CompanionLifecycleState.RELEASED;
    }

    private void resumeCorpseRemoval(@Nonnull Ref<EntityStore> ref,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        DeferredCorpseRemoval current = store.getComponent(ref, corpseRemovalType);
        if (current != null && !CompanionPermanentDeathHold.isHold(current)) {
            return;
        }
        String particles = current == null ? deathParticles(ref, store) : current.getDeathParticles();
        commandBuffer.putComponent(
                ref, corpseRemovalType, new DeferredCorpseRemoval(0.0, particles)
        );
    }

    @Nullable
    private static String deathParticles(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        return npc == null || npc.getRole() == null ? null : npc.getRole().getDeathParticles();
    }

    private static long saturatingAdd(long value, long delta) {
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }

    @Nonnull
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
