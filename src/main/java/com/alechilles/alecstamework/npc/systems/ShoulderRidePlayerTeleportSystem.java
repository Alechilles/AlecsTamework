package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkShoulderRideComponent;
import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.OrderPriority;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Detaches shoulder companions before their owner transfers or teleports.
 *
 * <p>Hytale only clears {@link MountedComponent} from the entity receiving a
 * {@link Teleport}. A shoulder companion is instead a passenger of that
 * player, so its mount reference must be cleared before the player moves to
 * another world.</p>
 */
public final class ShoulderRidePlayerTeleportSystem
        extends RefChangeSystem<EntityStore, Teleport> {
    private final ComponentType<EntityStore, Player> playerType;
    private final ComponentType<EntityStore, Teleport> teleportType;
    private final ComponentType<EntityStore, MountedByComponent> mountedByType;
    private final ComponentType<EntityStore, MountedComponent> mountedType;
    private final ComponentType<EntityStore, TameworkShoulderRideComponent>
            shoulderRideType;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE,
                    TeleportSystems.MoveSystem.class, OrderPriority.CLOSEST),
            new SystemDependency<>(Order.BEFORE,
                    TeleportSystems.PlayerMoveSystem.class, OrderPriority.CLOSEST));

    public ShoulderRidePlayerTeleportSystem(
            ComponentType<EntityStore, Player> playerType,
            ComponentType<EntityStore, Teleport> teleportType,
            ComponentType<EntityStore, MountedByComponent> mountedByType,
            ComponentType<EntityStore, MountedComponent> mountedType,
            ComponentType<EntityStore, TameworkShoulderRideComponent>
                    shoulderRideType) {
        this.playerType = playerType;
        this.teleportType = teleportType;
        this.mountedByType = mountedByType;
        this.mountedType = mountedType;
        this.shoulderRideType = shoulderRideType;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(playerType, mountedByType);
    }

    @Nonnull
    @Override
    public ComponentType<EntityStore, Teleport> componentType() {
        return teleportType;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> playerRef,
                                 @Nonnull Teleport teleport,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull CommandBuffer<EntityStore> commands) {
        MountedByComponent mountedBy = commands.getComponent(playerRef,
                mountedByType);
        if (mountedBy == null) {
            return;
        }
        for (Ref<EntityStore> passenger : new ArrayList<>(
                mountedBy.getPassengers())) {
            detachShoulderPassenger(playerRef, passenger, store, commands);
        }
    }

    @Override
    public void onComponentSet(@Nonnull Ref<EntityStore> playerRef,
                               @Nullable Teleport oldTeleport,
                               @Nonnull Teleport newTeleport,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commands) {
        // Teleport requests are acted upon when first added.
    }

    @Override
    public void onComponentRemoved(@Nonnull Ref<EntityStore> playerRef,
                                   @Nonnull Teleport teleport,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> commands) {
        // Nothing remains to detach after teleport completion.
    }

    private void detachShoulderPassenger(
            Ref<EntityStore> playerRef, Ref<EntityStore> passenger,
            Store<EntityStore> store, CommandBuffer<EntityStore> commands) {
        if (passenger == null || !passenger.isValid()
                || passenger.getStore() != store
                || commands.getComponent(passenger, shoulderRideType) == null) {
            return;
        }
        MountedComponent mounted = commands.getComponent(passenger,
                mountedType);
        if (mounted != null && playerRef.equals(mounted.getMountedToEntity())) {
            commands.tryRemoveComponent(passenger, mountedType);
        }
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }
}
