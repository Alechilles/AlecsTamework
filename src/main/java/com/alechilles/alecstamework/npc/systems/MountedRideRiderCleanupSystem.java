package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.npc.network.MountedRidePacketHandler;
import com.hypixel.hytale.builtin.mounts.MountSystems;
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
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Cleans rider-side Tamework ride state when the mounted NPC disappears before its cleanup system can tick.
 */
public final class MountedRideRiderCleanupSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, MountedComponent> mountedComponentType;
    private final ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderComponentType;
    private final ComponentType<EntityStore, TameworkRideMountComponent> rideMountComponentType;
    private final ComponentType<EntityStore, UUIDComponent> uuidComponentType;
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    private final ComponentType<EntityStore, DeathComponent> deathComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, MountSystems.HandleMountInput.class),
            new SystemDependency<>(Order.BEFORE, PlayerSystems.ProcessPlayerInput.class)
    );

    public MountedRideRiderCleanupSystem(
            @Nonnull ComponentType<EntityStore, MountedComponent> mountedComponentType,
            @Nonnull ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderComponentType,
            @Nonnull ComponentType<EntityStore, TameworkRideMountComponent> rideMountComponentType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidComponentType,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcComponentType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathComponentType) {
        this.mountedComponentType = mountedComponentType;
        this.rideRiderComponentType = rideRiderComponentType;
        this.rideMountComponentType = rideMountComponentType;
        this.uuidComponentType = uuidComponentType;
        this.npcComponentType = npcComponentType;
        this.deathComponentType = deathComponentType;
        this.query = rideRiderComponentType;
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> riderRef = archetypeChunk.getReferenceTo(index);
        TameworkRideRiderComponent rider = archetypeChunk.getComponent(index, rideRiderComponentType);
        if (riderRef == null || rider == null) {
            return;
        }
        MountedComponent mounted = store.getComponent(riderRef, mountedComponentType);
        Ref<EntityStore> mountRef = resolveMountRef(rider, mounted, store);
        TameworkRideMountComponent mount = mountRef == null || !mountRef.isValid()
                ? null
                : store.getComponent(mountRef, rideMountComponentType);
        boolean riderDead = store.getComponent(riderRef, deathComponentType) != null;
        boolean mountMissing = mountRef == null;
        boolean mountInvalid = mountRef != null && !mountRef.isValid();
        boolean mountComponentMissing = mountRef != null && mountRef.isValid() && mount == null;
        boolean uuidMismatch = mountRef != null
                && mountRef.isValid()
                && mount != null
                && !matchesMountUuid(rider, mountRef, store);
        boolean invalid = riderDead
                || mountMissing
                || mountInvalid
                || mountComponentMissing
                || uuidMismatch;
        if (!invalid) {
            return;
        }
        logCleanupReason(rider, riderDead, mountMissing, mountInvalid, mountComponentMissing, uuidMismatch);
        cleanupRiderAndMount(riderRef, mountRef, mount, commandBuffer);
    }

    @Nullable
    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkRideRiderComponent rider,
                                             @Nullable MountedComponent mounted,
                                             @Nonnull Store<EntityStore> store) {
        String mountUuid = rider.getMountUuid();
        if (!mountUuid.isBlank()) {
            try {
                return store.getExternalData().getWorld().getEntityRef(UUID.fromString(mountUuid));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return mounted != null && mounted.getMountedToEntity() != null && mounted.getMountedToEntity().isValid()
                ? mounted.getMountedToEntity()
                : null;
    }

    private boolean matchesMountUuid(@Nonnull TameworkRideRiderComponent rider,
                                     @Nonnull Ref<EntityStore> mountRef,
                                     @Nonnull Store<EntityStore> store) {
        if (rider.getMountUuid().isBlank()) {
            return true;
        }
        UUIDComponent uuid = store.getComponent(mountRef, uuidComponentType);
        return uuid != null && rider.getMountUuid().equals(uuid.getUuid().toString());
    }

    private void cleanupRiderAndMount(@Nonnull Ref<EntityStore> riderRef,
                                      @Nullable Ref<EntityStore> mountRef,
                                      @Nullable TameworkRideMountComponent mount,
                                      @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        commandBuffer.run(bufferStore -> {
            UUIDComponent riderUuid = bufferStore.getComponent(riderRef, uuidComponentType);
            if (riderUuid != null && riderUuid.getUuid() != null) {
                MountedRidePacketHandler.unregisterRide(riderUuid.getUuid());
            }
            if (riderRef.isValid()) {
                MountedRideClientAttachment.detach(bufferStore, riderRef);
                bufferStore.tryRemoveComponent(riderRef, rideRiderComponentType);
                bufferStore.tryRemoveComponent(riderRef, mountedComponentType);
            }
            if (mountRef != null && mountRef.isValid() && mount != null) {
                if (riderRef.isValid()) {
                    MountedRideClientAttachment.placeRiderAtMountAnchor(bufferStore, riderRef, mountRef, mount);
                }
                NPCEntity npc = bufferStore.getComponent(mountRef, npcComponentType);
                if (npc != null) {
                    restoreNpcState(mountRef, npc, mount, bufferStore);
                }
                bufferStore.tryRemoveComponent(mountRef, rideMountComponentType);
            }
        });
    }

    private void logCleanupReason(@Nonnull TameworkRideRiderComponent rider,
                                  boolean riderDead,
                                  boolean mountMissing,
                                  boolean mountInvalid,
                                  boolean mountComponentMissing,
                                  boolean uuidMismatch) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkRide debug: cleanup source=riderCleanup mountUuid=%s riderDead=%s mountMissing=%s " +
                        "mountInvalid=%s mountComponentMissing=%s uuidMismatch=%s",
                rider.getMountUuid(),
                riderDead,
                mountMissing,
                mountInvalid,
                mountComponentMissing,
                uuidMismatch
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
