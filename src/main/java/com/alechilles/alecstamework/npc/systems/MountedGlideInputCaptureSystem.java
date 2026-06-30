package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
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
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Captures rider input snapshots for the mounted glide controller before vanilla mount handling.
 */
public final class MountedGlideInputCaptureSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCMountComponent> npcMountComponentType;
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesComponentType;
    private final ComponentType<EntityStore, HeadRotation> headRotationComponentType;
    private final ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderComponentType;
    private final ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType;
    private final ComponentType<EntityStore, UUIDComponent> uuidComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, com.hypixel.hytale.server.npc.systems.RoleSystems.BehaviourTickSystem.class)
    );

    public MountedGlideInputCaptureSystem(
            @Nonnull ComponentType<EntityStore, NPCMountComponent> npcMountComponentType,
            @Nonnull ComponentType<EntityStore, MovementStatesComponent> movementStatesComponentType,
            @Nonnull ComponentType<EntityStore, HeadRotation> headRotationComponentType,
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderComponentType,
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidComponentType) {
        this.npcMountComponentType = npcMountComponentType;
        this.movementStatesComponentType = movementStatesComponentType;
        this.headRotationComponentType = headRotationComponentType;
        this.riderComponentType = riderComponentType;
        this.mountComponentType = mountComponentType;
        this.uuidComponentType = uuidComponentType;
        this.query = Query.and(mountComponentType, npcMountComponentType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        TameworkMountedGlideComponent mount = archetypeChunk.getComponent(index, mountComponentType);
        NPCMountComponent npcMount = archetypeChunk.getComponent(index, npcMountComponentType);
        if (mount == null || npcMount == null) {
            return;
        }
        Ref<EntityStore> mountRef = archetypeChunk.getReferenceTo(index);
        Ref<EntityStore> riderRef = resolveRiderRef(mount, store);
        if (riderRef == null || !riderRef.isValid()) {
            return;
        }
        TameworkMountedGlideRiderComponent rider = store.getComponent(riderRef, riderComponentType);
        if (rider == null || !matchesMountUuid(rider, mountRef, commandBuffer) || !npcMountStillOwnedByRider(npcMount, mount)) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean captured = captureRiderControls(mount, riderRef, store, now);
        captured |= captureRiderLook(mount, riderRef, store, now);
        if (captured) {
            commandBuffer.putComponent(mountRef, mountComponentType, mount);
        }
    }

    private boolean captureRiderControls(@Nonnull TameworkMountedGlideComponent mount,
                                         @Nonnull Ref<EntityStore> riderRef,
                                         @Nonnull Store<EntityStore> store,
                                         long now) {
        MovementStatesComponent movementStates = store.getComponent(riderRef, movementStatesComponentType);
        if (movementStates == null) {
            mount.captureControls(false, false, false, now);
            return true;
        }
        captureStates(mount, movementStates.getMovementStates(), now);
        return true;
    }

    private boolean captureRiderLook(@Nonnull TameworkMountedGlideComponent mount,
                                     @Nonnull Ref<EntityStore> riderRef,
                                     @Nonnull Store<EntityStore> store,
                                     long now) {
        HeadRotation headRotation = store.getComponent(riderRef, headRotationComponentType);
        if (headRotation == null || headRotation.getRotation() == null) {
            mount.setHasLookRotation(false);
            return true;
        }
        mount.captureLookRotation(
                (float) Math.toDegrees(headRotation.getRotation().yaw()),
                (float) Math.toDegrees(headRotation.getRotation().pitch()),
                (float) Math.toDegrees(headRotation.getRotation().roll()),
                now
        );
        return true;
    }

    private void captureStates(@Nonnull TameworkMountedGlideComponent mount,
                               @Nullable MovementStates states,
                               long now) {
        if (states == null) {
            mount.captureControls(false, false, false, now);
            return;
        }
        mount.captureControls(
                states.jumping || states.swimJumping,
                states.sprinting || states.running,
                states.crouching || states.forcedCrouching,
                now
        );
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

    private boolean matchesMountUuid(@Nonnull TameworkMountedGlideRiderComponent rider,
                                     @Nonnull Ref<EntityStore> mountRef,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (rider.getMountUuid().isBlank()) {
            return true;
        }
        UUIDComponent mountUuid = commandBuffer.getComponent(mountRef, uuidComponentType);
        return mountUuid != null && mountUuid.getUuid() != null && rider.getMountUuid().equals(mountUuid.getUuid().toString());
    }

    private boolean npcMountStillOwnedByRider(@Nonnull NPCMountComponent npcMount,
                                              @Nonnull TameworkMountedGlideComponent mount) {
        if (npcMount.getOwnerPlayerRef() == null || npcMount.getOwnerPlayerRef().getUuid() == null) {
            return false;
        }
        return mount.getRiderUuid().equals(npcMount.getOwnerPlayerRef().getUuid().toString());
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
