package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
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
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.systems.RoleSystems;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tears down Tamework ride links and restores the NPC when the rider dismounts or either side becomes invalid.
 */
public final class MountedRideCleanupSystem extends EntityTickingSystem<EntityStore> {
    private static final double MAX_RIDE_DISTANCE_SQUARED = 128.0 * 128.0;

    private final ComponentType<EntityStore, MountedComponent> mountedComponentType;
    private final ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderComponentType;
    private final ComponentType<EntityStore, TameworkRideMountComponent> rideMountComponentType;
    private final ComponentType<EntityStore, UUIDComponent> uuidComponentType;
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    private final ComponentType<EntityStore, DeathComponent> deathComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, RoleSystems.BehaviourTickSystem.class)
    );

    public MountedRideCleanupSystem(
            @Nonnull ComponentType<EntityStore, MountedComponent> mountedComponentType,
            @Nonnull ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderComponentType,
            @Nonnull ComponentType<EntityStore, TameworkRideMountComponent> rideMountComponentType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidComponentType,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcComponentType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformComponentType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathComponentType) {
        this.mountedComponentType = mountedComponentType;
        this.rideRiderComponentType = rideRiderComponentType;
        this.rideMountComponentType = rideMountComponentType;
        this.uuidComponentType = uuidComponentType;
        this.npcComponentType = npcComponentType;
        this.transformComponentType = transformComponentType;
        this.deathComponentType = deathComponentType;
        this.query = Query.and(rideMountComponentType, npcComponentType, uuidComponentType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> mountRef = archetypeChunk.getReferenceTo(index);
        TameworkRideMountComponent mount = archetypeChunk.getComponent(index, rideMountComponentType);
        NPCEntity npc = archetypeChunk.getComponent(index, npcComponentType);
        if (mountRef == null || mount == null || npc == null) {
            return;
        }
        Ref<EntityStore> riderRef = resolveRiderRef(mount, store);
        boolean invalid = mount.isDismountRequested()
                || store.getComponent(mountRef, deathComponentType) != null
                || riderRef == null
                || !riderRef.isValid()
                || store.getComponent(riderRef, deathComponentType) != null
                || !riderStillLinkedTo(riderRef, mountRef, store)
                || exceedsSanityDistance(mountRef, riderRef, store);
        if (!invalid) {
            return;
        }
        cleanupRide(mountRef, riderRef, npc, mount, commandBuffer);
    }

    @Nullable
    private Ref<EntityStore> resolveRiderRef(@Nonnull TameworkRideMountComponent mount,
                                             @Nonnull Store<EntityStore> store) {
        if (mount.getRiderUuid().isBlank()) {
            return null;
        }
        try {
            UUID riderUuid = UUID.fromString(mount.getRiderUuid());
            return store.getExternalData().getWorld().getEntityRef(riderUuid);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean riderStillLinkedTo(@Nonnull Ref<EntityStore> riderRef,
                                       @Nonnull Ref<EntityStore> mountRef,
                                       @Nonnull Store<EntityStore> store) {
        TameworkRideRiderComponent rider = store.getComponent(riderRef, rideRiderComponentType);
        if (rider == null || rider.getMountUuid().isBlank()) {
            return false;
        }
        UUIDComponent mountUuid = store.getComponent(mountRef, uuidComponentType);
        return mountUuid != null && rider.getMountUuid().equals(mountUuid.getUuid().toString());
    }

    private boolean exceedsSanityDistance(@Nonnull Ref<EntityStore> mountRef,
                                          @Nonnull Ref<EntityStore> riderRef,
                                          @Nonnull Store<EntityStore> store) {
        TransformComponent mountTransform = store.getComponent(mountRef, transformComponentType);
        TransformComponent riderTransform = store.getComponent(riderRef, transformComponentType);
        if (mountTransform == null || riderTransform == null) {
            return false;
        }
        Vector3d mountPos = mountTransform.getPosition();
        Vector3d riderPos = riderTransform.getPosition();
        double dx = mountPos.x - riderPos.x;
        double dy = mountPos.y - riderPos.y;
        double dz = mountPos.z - riderPos.z;
        return dx * dx + dy * dy + dz * dz > MAX_RIDE_DISTANCE_SQUARED;
    }

    private void cleanupRide(@Nonnull Ref<EntityStore> mountRef,
                             @Nullable Ref<EntityStore> riderRef,
                             @Nonnull NPCEntity npc,
                             @Nonnull TameworkRideMountComponent mount,
                             @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        commandBuffer.run(bufferStore -> {
            if (mountRef.isValid()) {
                restoreNpcState(mountRef, npc, mount, bufferStore);
                bufferStore.tryRemoveComponent(mountRef, rideMountComponentType);
            }
            if (riderRef != null && riderRef.isValid()) {
                MountedRideClientAttachment.detach(bufferStore, riderRef);
                bufferStore.tryRemoveComponent(riderRef, rideRiderComponentType);
                bufferStore.tryRemoveComponent(riderRef, mountedComponentType);
            }
        });
    }

    private void restoreNpcState(@Nonnull Ref<EntityStore> mountRef,
                                 @Nonnull NPCEntity npc,
                                 @Nonnull TameworkRideMountComponent mount,
                                 @Nonnull Store<EntityStore> store) {
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        if (!mount.getPreviousMotionController().isBlank()) {
            role.setActiveMotionController(mountRef, npc, mount.getPreviousMotionController(), store);
        }
        applyState(role, mountRef, store, mount.getPreviousState(), mount.getPreviousSubState());
    }

    private void applyState(@Nonnull Role role,
                            @Nonnull Ref<EntityStore> mountRef,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull String state,
                            @Nonnull String subState) {
        if (state.isBlank() || role.getStateSupport() == null) {
            return;
        }
        StateSupport support = role.getStateSupport();
        if (support.getStateHelper() != null && support.getStateHelper().getStateIndex(state) == StateSupport.NO_STATE) {
            return;
        }
        support.setState(mountRef, state, subState, store);
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
