package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.hypixel.hytale.builtin.mounts.MountSystems;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
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
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.systems.RoleSystems;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Captures raw rider input for the mounted glide controller before vanilla player movement consumes it.
 */
public final class MountedGlideInputCaptureSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, MountedComponent> mountedComponentType;
    private final ComponentType<EntityStore, PlayerInput> playerInputComponentType;
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesComponentType;
    private final ComponentType<EntityStore, HeadRotation> headRotationComponentType;
    private final ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderComponentType;
    private final ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType;
    private final ComponentType<EntityStore, UUIDComponent> uuidComponentType;
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, MountSystems.HandleMountInput.class),
            new SystemDependency<>(Order.BEFORE, PlayerSystems.ProcessPlayerInput.class),
            new SystemDependency<>(Order.BEFORE, RoleSystems.BehaviourTickSystem.class)
    );

    public MountedGlideInputCaptureSystem(
            @Nonnull ComponentType<EntityStore, MountedComponent> mountedComponentType,
            @Nonnull ComponentType<EntityStore, PlayerInput> playerInputComponentType,
            @Nonnull ComponentType<EntityStore, MovementStatesComponent> movementStatesComponentType,
            @Nonnull ComponentType<EntityStore, HeadRotation> headRotationComponentType,
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderComponentType,
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidComponentType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformComponentType) {
        this.mountedComponentType = mountedComponentType;
        this.playerInputComponentType = playerInputComponentType;
        this.movementStatesComponentType = movementStatesComponentType;
        this.headRotationComponentType = headRotationComponentType;
        this.riderComponentType = riderComponentType;
        this.mountComponentType = mountComponentType;
        this.uuidComponentType = uuidComponentType;
        this.transformComponentType = transformComponentType;
        this.query = Query.and(playerInputComponentType, riderComponentType);
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
        Ref<EntityStore> mountRef = resolveMountRef(rider, store);
        if (mountRef == null || !mountRef.isValid()) {
            return;
        }
        TameworkMountedGlideComponent mount = commandBuffer.getComponent(mountRef, mountComponentType);
        MountedComponent mounted = commandBuffer.getComponent(riderRef, mountedComponentType);
        if (mount == null || mounted == null || !matchesMountUuid(rider, mountRef, commandBuffer)
                || !mountedStillAttachedToMount(mounted, mountRef)) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean captured = captureQueuedInput(mount, playerInput, riderRef, commandBuffer, now);
        if (!captured) {
            captured = captureCurrentRiderSnapshot(mount, riderRef, store, now);
        }
        if (captured) {
            commandBuffer.putComponent(mountRef, mountComponentType, mount);
        }
    }

    private boolean captureQueuedInput(@Nonnull TameworkMountedGlideComponent mount,
                                       @Nonnull PlayerInput playerInput,
                                       @Nonnull Ref<EntityStore> riderRef,
                                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                       long now) {
        List<PlayerInput.InputUpdate> queue = playerInput.getMovementUpdateQueue();
        if (queue.isEmpty()) {
            return false;
        }
        boolean captured = false;
        boolean sawMovementIntent = false;
        boolean sawLook = false;
        boolean sawControls = false;
        for (PlayerInput.InputUpdate inputUpdate : queue) {
            if (inputUpdate instanceof PlayerInput.WishMovement wish) {
                captureWish(mount, wish.getX(), wish.getZ(), now);
                sawMovementIntent = true;
                captured = true;
            } else if (inputUpdate instanceof PlayerInput.RelativeMovement relative) {
                captureWorldMovement(mount, relative.getX(), relative.getZ(), false, now);
                sawMovementIntent = true;
                captured = true;
            } else if (inputUpdate instanceof PlayerInput.AbsoluteMovement absolute) {
                captureAbsoluteMovement(mount, riderRef, absolute, commandBuffer, now);
                sawMovementIntent = true;
                captured = true;
            } else if (inputUpdate instanceof PlayerInput.SetClientVelocity velocity) {
                captureVelocityMovement(mount, velocity, now);
                sawMovementIntent = true;
                captured = true;
            } else if (inputUpdate instanceof PlayerInput.SetBody body) {
                captureDirectionLook(mount, body.direction(), now);
                sawLook = true;
                captured = true;
            } else if (inputUpdate instanceof PlayerInput.SetHead head) {
                captureDirectionLook(mount, head.direction(), now);
                sawLook = true;
                captured = true;
            } else if (inputUpdate instanceof PlayerInput.SetMovementStates states) {
                captureStates(mount, states.movementStates(), now);
                sawControls = true;
                captured = true;
            } else if (inputUpdate instanceof PlayerInput.SetRiderMovementStates riderStates) {
                captureStates(mount, riderStates.movementStates(), now);
                sawControls = true;
                captured = true;
            }
        }
        if (!sawMovementIntent) {
            clearMovementIntent(mount, now);
        }
        if (!sawLook) {
            captureRiderLook(mount, riderRef, commandBuffer, now);
        }
        if (!sawControls) {
            captureRiderControls(mount, riderRef, commandBuffer, now);
        }
        return captured;
    }

    private boolean captureCurrentRiderSnapshot(@Nonnull TameworkMountedGlideComponent mount,
                                                @Nonnull Ref<EntityStore> riderRef,
                                                @Nonnull Store<EntityStore> store,
                                                long now) {
        boolean controls = captureRiderControls(mount, riderRef, store, now);
        boolean look = captureRiderLook(mount, riderRef, store, now);
        return controls || look;
    }

    private boolean captureRiderControls(@Nonnull TameworkMountedGlideComponent mount,
                                         @Nonnull Ref<EntityStore> riderRef,
                                         @Nonnull ComponentAccessor<EntityStore> componentAccessor,
                                         long now) {
        MovementStatesComponent movementStates = componentAccessor.getComponent(riderRef, movementStatesComponentType);
        if (movementStates == null) {
            mount.captureControls(false, false, false, now);
            return true;
        }
        captureStates(mount, movementStates.getMovementStates(), now);
        return true;
    }

    private boolean captureRiderLook(@Nonnull TameworkMountedGlideComponent mount,
                                     @Nonnull Ref<EntityStore> riderRef,
                                     @Nonnull ComponentAccessor<EntityStore> componentAccessor,
                                     long now) {
        HeadRotation headRotation = componentAccessor.getComponent(riderRef, headRotationComponentType);
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

    private void captureWish(@Nonnull TameworkMountedGlideComponent mount,
                             double wishX,
                             double wishZ,
                             long now) {
        double horizontalLength = Math.sqrt(wishX * wishX + wishZ * wishZ);
        if (horizontalLength <= 0.0001) {
            clearMovementIntent(mount, now);
            return;
        }
        double scale = horizontalLength > 1.0 ? 1.0 / horizontalLength : 1.0;
        mount.captureMovementIntent(wishZ * scale, wishX * scale, now);
    }

    private void captureAbsoluteMovement(@Nonnull TameworkMountedGlideComponent mount,
                                         @Nonnull Ref<EntityStore> riderRef,
                                         @Nonnull PlayerInput.AbsoluteMovement absolute,
                                         @Nonnull ComponentAccessor<EntityStore> componentAccessor,
                                         long now) {
        TransformComponent transform = componentAccessor.getComponent(riderRef, transformComponentType);
        if (transform == null) {
            return;
        }
        Vector3d position = transform.getPosition();
        captureWorldMovement(mount, absolute.getX() - position.x, absolute.getZ() - position.z, true, now);
    }

    private void captureVelocityMovement(@Nonnull TameworkMountedGlideComponent mount,
                                         @Nonnull PlayerInput.SetClientVelocity velocity,
                                         long now) {
        if (mount.hasMovementIntent() || velocity.getVelocity() == null) {
            return;
        }
        Vector3d value = velocity.getVelocity();
        captureWorldMovement(mount, value.x, value.z, true, now);
    }

    private void captureWorldMovement(@Nonnull TameworkMountedGlideComponent mount,
                                      double worldX,
                                      double worldZ,
                                      boolean normalizeIntent,
                                      long now) {
        double horizontalLength = Math.sqrt(worldX * worldX + worldZ * worldZ);
        if (horizontalLength <= 0.0001) {
            clearMovementIntent(mount, now);
            return;
        }
        double normalizedX = normalizeIntent ? worldX / horizontalLength : worldX;
        double normalizedZ = normalizeIntent ? worldZ / horizontalLength : worldZ;
        if (!normalizeIntent && horizontalLength > 1.0) {
            normalizedX = worldX / horizontalLength;
            normalizedZ = worldZ / horizontalLength;
        }
        double yaw = mount.hasLookRotation() ? Math.toRadians(mount.getLookYawDegrees()) : 0.0;
        double forwardX = -Math.sin(yaw);
        double forwardZ = -Math.cos(yaw);
        double rightX = -Math.sin(yaw - Math.PI / 2.0);
        double rightZ = -Math.cos(yaw - Math.PI / 2.0);
        double strafe = clamp(normalizedX * rightX + normalizedZ * rightZ, -1.0, 1.0);
        double forward = clamp(normalizedX * forwardX + normalizedZ * forwardZ, -1.0, 1.0);
        mount.captureMovementIntent(forward, strafe, now);
    }

    private void captureDirectionLook(@Nonnull TameworkMountedGlideComponent mount,
                                      @Nullable Direction direction,
                                      long now) {
        if (direction == null) {
            return;
        }
        mount.captureLookRotation(
                (float) Math.toDegrees(direction.yaw),
                (float) Math.toDegrees(direction.pitch),
                (float) Math.toDegrees(direction.roll),
                now
        );
    }

    private void clearMovementIntent(@Nonnull TameworkMountedGlideComponent mount, long now) {
        mount.setHasMovementIntent(false);
        mount.setForwardIntent(0.0);
        mount.setStrafeIntent(0.0);
        mount.setLastInputAtMs(now);
    }

    @Nullable
    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkMountedGlideRiderComponent rider,
                                             @Nonnull Store<EntityStore> store) {
        if (rider.getMountUuid().isBlank()) {
            return null;
        }
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(rider.getMountUuid()));
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

    private boolean mountedStillAttachedToMount(@Nonnull MountedComponent mounted,
                                                @Nonnull Ref<EntityStore> mountRef) {
        Ref<EntityStore> mountedTo = mounted.getMountedToEntity();
        return mountedTo != null && mountedTo.isValid() && mountedTo.equals(mountRef);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(min, Math.min(max, value));
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
