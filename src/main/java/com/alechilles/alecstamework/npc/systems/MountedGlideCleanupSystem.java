package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.alechilles.alecstamework.npc.network.MountedGlidePacketHandler;
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
 * Tears down stale mounted glide sessions and restores the NPC state captured at mount time.
 */
public final class MountedGlideCleanupSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, MountedComponent> mountedComponentType;
    private final ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderComponentType;
    private final ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType;
    private final ComponentType<EntityStore, UUIDComponent> uuidComponentType;
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    private final ComponentType<EntityStore, DeathComponent> deathComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, RoleSystems.BehaviourTickSystem.class)
    );

    public MountedGlideCleanupSystem(
            @Nonnull ComponentType<EntityStore, MountedComponent> mountedComponentType,
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderComponentType,
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidComponentType,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcComponentType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathComponentType) {
        this.mountedComponentType = mountedComponentType;
        this.riderComponentType = riderComponentType;
        this.mountComponentType = mountComponentType;
        this.uuidComponentType = uuidComponentType;
        this.npcComponentType = npcComponentType;
        this.deathComponentType = deathComponentType;
        this.query = Query.and(mountComponentType, npcComponentType, uuidComponentType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> mountRef = archetypeChunk.getReferenceTo(index);
        TameworkMountedGlideComponent mount = archetypeChunk.getComponent(index, mountComponentType);
        NPCEntity npc = archetypeChunk.getComponent(index, npcComponentType);
        if (mountRef == null || mount == null || npc == null) {
            return;
        }
        Ref<EntityStore> riderRef = resolveRiderRef(mount, store);
        boolean mountDead = store.getComponent(mountRef, deathComponentType) != null;
        boolean riderMissing = riderRef == null;
        boolean riderInvalid = riderRef != null && !riderRef.isValid();
        boolean riderDead = riderRef != null
                && riderRef.isValid()
                && store.getComponent(riderRef, deathComponentType) != null;
        boolean linkMismatch = riderRef != null
                && riderRef.isValid()
                && !riderStillLinkedTo(riderRef, mountRef, store);
        if (mountDead || riderMissing || riderInvalid || riderDead || linkMismatch) {
            cleanupGlide(mountRef, riderRef, npc, mount, commandBuffer);
        }
    }

    @Nullable
    private Ref<EntityStore> resolveRiderRef(@Nonnull TameworkMountedGlideComponent mount,
                                             @Nonnull Store<EntityStore> store) {
        if (mount.getRiderUuid().isBlank()) {
            return null;
        }
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(mount.getRiderUuid()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean riderStillLinkedTo(@Nonnull Ref<EntityStore> riderRef,
                                       @Nonnull Ref<EntityStore> mountRef,
                                       @Nonnull Store<EntityStore> store) {
        TameworkMountedGlideRiderComponent rider = store.getComponent(riderRef, riderComponentType);
        if (rider == null || rider.getMountUuid().isBlank()) {
            return false;
        }
        UUIDComponent mountUuid = store.getComponent(mountRef, uuidComponentType);
        return mountUuid != null && mountUuid.getUuid() != null && rider.getMountUuid().equals(mountUuid.getUuid().toString());
    }

    private void cleanupGlide(@Nonnull Ref<EntityStore> mountRef,
                              @Nullable Ref<EntityStore> riderRef,
                              @Nonnull NPCEntity npc,
                              @Nonnull TameworkMountedGlideComponent mount,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        commandBuffer.run(bufferStore -> {
            MountedGlidePacketHandler.unregisterGlide(mount.getRiderUuid());
            if (mountRef.isValid()) {
                restoreNpcState(mountRef, npc, mount, bufferStore);
                bufferStore.tryRemoveComponent(mountRef, mountComponentType);
            }
            if (riderRef != null && riderRef.isValid()) {
                MountedRideClientAttachment.detach(bufferStore, riderRef);
                bufferStore.tryRemoveComponent(riderRef, riderComponentType);
                bufferStore.tryRemoveComponent(riderRef, mountedComponentType);
            }
        });
    }

    private void restoreNpcState(@Nonnull Ref<EntityStore> mountRef,
                                 @Nonnull NPCEntity npc,
                                 @Nonnull TameworkMountedGlideComponent mount,
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
