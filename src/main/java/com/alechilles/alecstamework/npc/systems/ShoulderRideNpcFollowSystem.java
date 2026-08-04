package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkShoulderRideComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.ComputeVelocitySystem;
import java.util.Set;
import javax.annotation.Nonnull;

/** Pins a shoulder-mounted NPC's authoritative pose to its player mount. */
public final class ShoulderRideNpcFollowSystem
        extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, TameworkShoulderRideComponent> markerType;
    private final ComponentType<EntityStore, MountedComponent> mountedType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final ComponentType<EntityStore, Velocity> velocityType;
    private final ComponentType<EntityStore, DeathComponent> deathType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, ComputeVelocitySystem.class));

    public ShoulderRideNpcFollowSystem(
            ComponentType<EntityStore, TameworkShoulderRideComponent> markerType,
            ComponentType<EntityStore, MountedComponent> mountedType,
            ComponentType<EntityStore, TransformComponent> transformType,
            ComponentType<EntityStore, Velocity> velocityType,
            ComponentType<EntityStore, DeathComponent> deathType) {
        this.markerType = markerType;
        this.mountedType = mountedType;
        this.transformType = transformType;
        this.velocityType = velocityType;
        this.deathType = deathType;
        this.query = Query.and(markerType, transformType,
                NPCEntity.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commands) {
        Ref<EntityStore> npcRef = chunk.getReferenceTo(index);
        TameworkShoulderRideComponent marker =
                chunk.getComponent(index, markerType);
        MountedComponent mounted = commands.getComponent(npcRef, mountedType);
        Ref<EntityStore> playerRef = mounted == null
                ? null : mounted.getMountedToEntity();
        if (store.getComponent(npcRef, deathType) != null
                || !isValidTarget(marker, playerRef, store)) {
            commands.tryRemoveComponent(npcRef, mountedType);
            commands.tryRemoveComponent(npcRef, markerType);
            return;
        }
        TransformComponent playerTransform =
                store.getComponent(playerRef, transformType);
        TransformComponent npcTransform =
                commands.getComponent(npcRef, transformType);
        if (playerTransform == null || npcTransform == null) {
            commands.tryRemoveComponent(npcRef, mountedType);
            commands.tryRemoveComponent(npcRef, markerType);
            return;
        }
        npcTransform.setPosition(playerTransform.getPosition());
        Velocity velocity = commands.getComponent(npcRef, velocityType);
        if (velocity != null) velocity.setZero();
    }

    private boolean isValidTarget(TameworkShoulderRideComponent marker,
                                  Ref<EntityStore> playerRef,
                                  Store<EntityStore> store) {
        if (marker == null || marker.getOwnerUuid() == null || playerRef == null
                || !playerRef.isValid()
                || store.getComponent(playerRef, deathType) != null
                || store.getComponent(playerRef, Player.getComponentType()) == null) {
            return false;
        }
        UUIDComponent uuid = store.getComponent(playerRef,
                UUIDComponent.getComponentType());
        return uuid != null && marker.getOwnerUuid().equals(uuid.getUuid());
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
