package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.npc.network.MountedRidePacketHandler;
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
import com.hypixel.hytale.protocol.AnimationSlot;
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
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tears down Tamework ride links and restores the NPC when the rider dismounts or either side becomes invalid.
 */
public final class MountedRideCleanupSystem extends EntityTickingSystem<EntityStore> {
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
        boolean dismountRequested = mount.isDismountRequested();
        boolean mountDead = store.getComponent(mountRef, deathComponentType) != null;
        boolean riderMissing = riderRef == null;
        boolean riderInvalid = riderRef != null && !riderRef.isValid();
        boolean riderDead = riderRef != null
                && riderRef.isValid()
                && store.getComponent(riderRef, deathComponentType) != null;
        boolean linkMismatch = riderRef != null
                && riderRef.isValid()
                && !riderStillLinkedTo(riderRef, mountRef, store);
        boolean invalid = dismountRequested
                || mountDead
                || riderMissing
                || riderInvalid
                || riderDead
                || linkMismatch;
        if (!invalid) {
            return;
        }
        logCleanupReason(
                "mountCleanup",
                mount,
                dismountRequested,
                mountDead,
                riderMissing,
                riderInvalid,
                riderDead,
                linkMismatch
        );
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

    private void cleanupRide(@Nonnull Ref<EntityStore> mountRef,
                             @Nullable Ref<EntityStore> riderRef,
                             @Nonnull NPCEntity npc,
                             @Nonnull TameworkRideMountComponent mount,
                             @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        commandBuffer.run(bufferStore -> {
            MountedRidePacketHandler.unregisterRide(mount.getRiderUuid());
            if (mountRef.isValid()) {
                restoreNpcState(mountRef, npc, mount, bufferStore);
                bufferStore.tryRemoveComponent(mountRef, rideMountComponentType);
            }
            if (riderRef != null && riderRef.isValid()) {
                if (mountRef.isValid()) {
                    MountedRideClientAttachment.placeRiderAtMountAnchor(bufferStore, riderRef, mountRef, mount);
                }
                MountedRideClientAttachment.detach(bufferStore, riderRef);
                bufferStore.tryRemoveComponent(riderRef, rideRiderComponentType);
                bufferStore.tryRemoveComponent(riderRef, mountedComponentType);
            }
        });
    }

    private void logCleanupReason(@Nonnull String source,
                                  @Nonnull TameworkRideMountComponent mount,
                                  boolean dismountRequested,
                                  boolean mountDead,
                                  boolean riderMissing,
                                  boolean riderInvalid,
                                  boolean riderDead,
                                  boolean linkMismatch) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkRide debug: cleanup source=%s riderUuid=%s dismountRequested=%s mountDead=%s " +
                        "riderMissing=%s riderInvalid=%s riderDead=%s linkMismatch=%s",
                source,
                mount.getRiderUuid(),
                dismountRequested,
                mountDead,
                riderMissing,
                riderInvalid,
                riderDead,
                linkMismatch
        );
    }

    private void restoreNpcState(@Nonnull Ref<EntityStore> mountRef,
                                 @Nonnull NPCEntity npc,
                                 @Nonnull TameworkRideMountComponent mount,
                                 @Nonnull Store<EntityStore> store) {
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        npc.playAnimation(mountRef, AnimationSlot.Movement, null, store);
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
