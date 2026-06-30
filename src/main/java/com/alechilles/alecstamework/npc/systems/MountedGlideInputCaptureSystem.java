package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
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
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Captures rider input snapshots for the mounted glide controller before vanilla mount handling.
 */
public final class MountedGlideInputCaptureSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, MountedComponent> mountedComponentType;
    private final ComponentType<EntityStore, PlayerInput> playerInputComponentType;
    private final ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderComponentType;
    private final ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType;
    private final ComponentType<EntityStore, UUIDComponent> uuidComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, MountSystems.HandleMountInput.class),
            new SystemDependency<>(Order.BEFORE, PlayerSystems.ProcessPlayerInput.class)
    );

    public MountedGlideInputCaptureSystem(
            @Nonnull ComponentType<EntityStore, MountedComponent> mountedComponentType,
            @Nonnull ComponentType<EntityStore, PlayerInput> playerInputComponentType,
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderComponentType,
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidComponentType) {
        this.mountedComponentType = mountedComponentType;
        this.playerInputComponentType = playerInputComponentType;
        this.riderComponentType = riderComponentType;
        this.mountComponentType = mountComponentType;
        this.uuidComponentType = uuidComponentType;
        this.query = Query.and(playerInputComponentType, riderComponentType, mountedComponentType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerInput playerInput = archetypeChunk.getComponent(index, playerInputComponentType);
        TameworkMountedGlideRiderComponent rider = archetypeChunk.getComponent(index, riderComponentType);
        if (playerInput == null || rider == null) {
            return;
        }
        Ref<EntityStore> riderRef = archetypeChunk.getReferenceTo(index);
        MountedComponent mounted = archetypeChunk.getComponent(index, mountedComponentType);
        Ref<EntityStore> mountRef = resolveMountRef(rider, mounted, store);
        if (mountRef == null || !mountRef.isValid()) {
            clearStaleState(riderRef, commandBuffer);
            return;
        }
        TameworkMountedGlideComponent mount = commandBuffer.getComponent(mountRef, mountComponentType);
        if (mount == null || !matchesMountUuid(rider, mountRef, commandBuffer)) {
            clearStaleState(riderRef, commandBuffer);
            return;
        }
        List<PlayerInput.InputUpdate> queue = playerInput.getMovementUpdateQueue();
        if (queue.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean captured = false;
        ListIterator<PlayerInput.InputUpdate> inputIterator = queue.listIterator();
        while (inputIterator.hasNext()) {
            PlayerInput.InputUpdate inputUpdate = inputIterator.next();
            captured |= captureInputUpdate(mount, inputUpdate, now);
            if (shouldConsumeBeforeVanillaMountHandling(inputUpdate)) {
                inputIterator.remove();
            }
        }
        if (captured) {
            commandBuffer.putComponent(mountRef, mountComponentType, mount);
        }
    }

    private boolean captureInputUpdate(@Nonnull TameworkMountedGlideComponent mount,
                                       @Nonnull PlayerInput.InputUpdate inputUpdate,
                                       long now) {
        if (inputUpdate instanceof PlayerInput.WishMovement wish) {
            captureWish(mount, wish.getX(), wish.getZ(), now);
            return true;
        }
        if (inputUpdate instanceof PlayerInput.RelativeMovement relative) {
            captureWish(mount, relative.getX(), relative.getZ(), now);
            return true;
        }
        if (inputUpdate instanceof PlayerInput.AbsoluteMovement absolute) {
            captureWish(mount, absolute.getX(), absolute.getZ(), now);
            return true;
        }
        if (inputUpdate instanceof PlayerInput.SetBody body) {
            captureDirection(mount, body.direction(), now);
            return true;
        }
        if (inputUpdate instanceof PlayerInput.SetHead head) {
            captureDirection(mount, head.direction(), now);
            return true;
        }
        if (inputUpdate instanceof PlayerInput.SetMovementStates states) {
            captureStates(mount, states.movementStates(), now);
            return true;
        }
        if (inputUpdate instanceof PlayerInput.SetRiderMovementStates states) {
            captureStates(mount, states.movementStates(), now);
            return true;
        }
        return false;
    }

    private boolean shouldConsumeBeforeVanillaMountHandling(@Nonnull PlayerInput.InputUpdate inputUpdate) {
        return inputUpdate instanceof PlayerInput.WishMovement
                || inputUpdate instanceof PlayerInput.RelativeMovement
                || inputUpdate instanceof PlayerInput.AbsoluteMovement
                || inputUpdate instanceof PlayerInput.SetBody
                || inputUpdate instanceof PlayerInput.SetMovementStates
                || inputUpdate instanceof PlayerInput.SetRiderMovementStates;
    }

    private void captureWish(@Nonnull TameworkMountedGlideComponent mount, double strafe, double forward, long now) {
        mount.captureMovementIntent(forward, strafe, now);
    }

    private void captureDirection(@Nonnull TameworkMountedGlideComponent mount, @Nullable Direction direction, long now) {
        if (direction != null) {
            mount.captureLookRotation(direction.yaw, direction.pitch, direction.roll, now);
        }
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
    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkMountedGlideRiderComponent rider,
                                             @Nullable MountedComponent mounted,
                                             @Nonnull Store<EntityStore> store) {
        if (!rider.getMountUuid().isBlank()) {
            try {
                return store.getExternalData().getWorld().getEntityRef(UUID.fromString(rider.getMountUuid()));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return mounted != null && mounted.getMountedToEntity() != null && mounted.getMountedToEntity().isValid()
                ? mounted.getMountedToEntity()
                : null;
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

    private void clearStaleState(@Nullable Ref<EntityStore> riderRef,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (riderRef == null || !riderRef.isValid()) {
            return;
        }
        commandBuffer.run(bufferStore -> {
            bufferStore.tryRemoveComponent(riderRef, riderComponentType);
            bufferStore.tryRemoveComponent(riderRef, mountedComponentType);
        });
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
