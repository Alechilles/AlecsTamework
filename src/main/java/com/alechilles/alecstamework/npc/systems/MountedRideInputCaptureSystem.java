package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.hypixel.hytale.builtin.mounts.MountSystems;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.math.vector.Vector3d;
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
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.instructions.BodyMotion;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.systems.RoleSystems;
import com.hypixel.hytale.server.npc.systems.SteeringSystem;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Captures rider input for Tamework rides before vanilla mounted input mutates the target entity.
 */
public final class MountedRideInputCaptureSystem extends EntityTickingSystem<EntityStore> {
    private static final double FALLBACK_VERTICAL_SCALE = 20.0;
    private static final double MOUNT_TURN_STRAFE_SCALE = 12.0;
    private static final double MOUNT_TURN_STRAFE_DEAD_ZONE = 0.001;

    private final ComponentType<EntityStore, MountedComponent> mountedComponentType;
    private final ComponentType<EntityStore, PlayerInput> playerInputComponentType;
    private final ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderComponentType;
    private final ComponentType<EntityStore, TameworkRideMountComponent> rideMountComponentType;
    private final ComponentType<EntityStore, UUIDComponent> uuidComponentType;
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesComponentType;
    private long lastDebugMs;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, MountSystems.HandleMountInput.class),
            new SystemDependency<>(Order.BEFORE, PlayerSystems.ProcessPlayerInput.class),
            new SystemDependency<>(Order.BEFORE, RoleSystems.BehaviourTickSystem.class),
            new SystemDependency<>(Order.BEFORE, SteeringSystem.class)
    );

    public MountedRideInputCaptureSystem(
            @Nonnull ComponentType<EntityStore, MountedComponent> mountedComponentType,
            @Nonnull ComponentType<EntityStore, PlayerInput> playerInputComponentType,
            @Nonnull ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderComponentType,
            @Nonnull ComponentType<EntityStore, TameworkRideMountComponent> rideMountComponentType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidComponentType,
            @Nonnull ComponentType<EntityStore, MovementStatesComponent> movementStatesComponentType) {
        this.mountedComponentType = mountedComponentType;
        this.playerInputComponentType = playerInputComponentType;
        this.rideRiderComponentType = rideRiderComponentType;
        this.rideMountComponentType = rideMountComponentType;
        this.uuidComponentType = uuidComponentType;
        this.movementStatesComponentType = movementStatesComponentType;
        this.query = Query.and(playerInputComponentType, rideRiderComponentType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerInput playerInput = archetypeChunk.getComponent(index, playerInputComponentType);
        TameworkRideRiderComponent rider = archetypeChunk.getComponent(index, rideRiderComponentType);
        if (playerInput == null || rider == null) {
            return;
        }
        Ref<EntityStore> riderRef = archetypeChunk.getReferenceTo(index);
        MountedComponent mounted = archetypeChunk.getComponent(index, mountedComponentType);
        Ref<EntityStore> mountRef = resolveMountRef(rider, mounted, store);
        if (mountRef == null || !mountRef.isValid()) {
            clearStaleRideState(riderRef, null, commandBuffer);
            return;
        }
        TameworkRideMountComponent mount = commandBuffer.getComponent(mountRef, rideMountComponentType);
        if (mount == null || !matchesMountUuid(rider, mountRef, commandBuffer)) {
            clearStaleRideState(riderRef, mountRef, commandBuffer);
            return;
        }
        ensureRideState(mountRef, mount, commandBuffer);
        captureCurrentRiderRotation(mount, index, archetypeChunk);
        List<PlayerInput.InputUpdate> queue = playerInput.getMovementUpdateQueue();
        boolean capturedMountMovement = syncAuthoritativePose(mountRef, mount, commandBuffer, queue.isEmpty());
        if (queue.isEmpty()) {
            boolean hadWishMovement = mount.hasWishMovement();
            if (!capturedMountMovement) {
                mount.setHasWishMovement(false);
                mount.setWishX(0.0);
                mount.setWishY(0.0);
                mount.setWishZ(0.0);
            }
            maybeLogDebug(mount, mountRef, 0, "<empty>", commandBuffer);
            if (capturedMountMovement || hadWishMovement) {
                commandBuffer.putComponent(mountRef, rideMountComponentType, mount);
            }
            return;
        }
        String queueSummary = summarizeQueue(queue);

        mount.clearControlInputSnapshot();
        for (PlayerInput.InputUpdate inputUpdate : queue) {
            if (inputUpdate instanceof PlayerInput.WishMovement wish) {
                mount.captureWishMovement(wish.getX(), wish.getY(), wish.getZ());
            } else if (inputUpdate instanceof PlayerInput.RelativeMovement relative) {
                captureWorldMovement(mount, relative.getX(), relative.getY(), relative.getZ());
            } else if (inputUpdate instanceof PlayerInput.AbsoluteMovement absolute) {
                captureAbsoluteMovement(mount, mountRef, absolute, commandBuffer);
            } else if (inputUpdate instanceof PlayerInput.SetClientVelocity velocity) {
                captureVelocityFallback(mount, velocity);
            } else if (inputUpdate instanceof PlayerInput.SetBody body) {
                captureBody(mount, body.direction());
            } else if (inputUpdate instanceof PlayerInput.SetHead head) {
                captureHead(mount, head.direction());
            } else if (inputUpdate instanceof PlayerInput.SetMovementStates states) {
                captureStates(mount, states.movementStates());
            } else if (inputUpdate instanceof PlayerInput.SetRiderMovementStates riderStates) {
                captureStates(mount, riderStates.movementStates());
            }
            applyRiderLocalInput(inputUpdate, index, archetypeChunk, commandBuffer);
        }
        captureCurrentRiderRotation(mount, index, archetypeChunk);
        mount.setLastInputAtMs(System.currentTimeMillis());
        maybeLogDebug(mount, mountRef, queue.size(), queueSummary, commandBuffer);
        commandBuffer.putComponent(mountRef, rideMountComponentType, mount);
        queue.clear();
    }

    private boolean syncAuthoritativePose(@Nonnull Ref<EntityStore> mountRef,
                                          @Nonnull TameworkRideMountComponent mount,
                                          @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                          boolean captureExternalMountMovement) {
        TransformComponent transform = commandBuffer.getComponent(mountRef, TransformComponent.getComponentType());
        if (transform == null || transform.getRotation() == null) {
            return false;
        }
        if (!mount.hasAuthoritativePose()) {
            mount.captureAuthoritativePose(
                    transform.getPosition().x,
                    transform.getPosition().y,
                    transform.getPosition().z,
                    transform.getRotation().getYaw(),
                    transform.getRotation().getPitch(),
                    transform.getRotation().getRoll()
            );
            commandBuffer.putComponent(mountRef, rideMountComponentType, mount);
            return false;
        }
        double dx = transform.getPosition().x - mount.getAuthoritativeX();
        double dy = transform.getPosition().y - mount.getAuthoritativeY();
        double dz = transform.getPosition().z - mount.getAuthoritativeZ();
        float yawDelta = normalizeAngle(transform.getRotation().getYaw() - mount.getAuthoritativeYaw());
        float pitchDelta = normalizeAngle(transform.getRotation().getPitch() - mount.getAuthoritativePitch());
        float rollDelta = normalizeAngle(transform.getRotation().getRoll() - mount.getAuthoritativeRoll());
        if (dx * dx + dy * dy + dz * dz < 1.0E-8
                && yawDelta * yawDelta + pitchDelta * pitchDelta + rollDelta * rollDelta < 1.0E-8f) {
            return false;
        }
        if (captureExternalMountMovement) {
            captureWorldMovement(mount, dx, dy, dz, true);
            captureMountTurnAsStrafe(mount, yawDelta);
            MovementStatesComponent movementStates = commandBuffer.getComponent(mountRef, movementStatesComponentType);
            if (movementStates != null) {
                captureStates(mount, movementStates.getMovementStates());
            }
        }
        transform.getPosition().x = mount.getAuthoritativeX();
        transform.getPosition().y = mount.getAuthoritativeY();
        transform.getPosition().z = mount.getAuthoritativeZ();
        transform.getRotation().setYaw(mount.getAuthoritativeYaw());
        transform.getRotation().setPitch(mount.getAuthoritativePitch());
        transform.getRotation().setRoll(mount.getAuthoritativeRoll());
        return captureExternalMountMovement && mount.hasWishMovement();
    }

    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkRideRiderComponent rider,
                                             MountedComponent mounted,
                                             @Nonnull Store<EntityStore> store) {
        if (mounted != null && mounted.getMountedToEntity() != null && mounted.getMountedToEntity().isValid()) {
            return mounted.getMountedToEntity();
        }
        String mountUuid = rider.getMountUuid();
        if (mountUuid.isBlank()) {
            return null;
        }
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(mountUuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void ensureRideState(@Nonnull Ref<EntityStore> mountRef,
                                 @Nonnull TameworkRideMountComponent mount,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        NPCEntity npc = commandBuffer.getComponent(mountRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        Role role = npc.getRole();
        StateSupport support = role.getStateSupport();
        String rideState = mount.getRideState();
        if (support == null || rideState == null || rideState.isBlank()) {
            return;
        }
        if (support.getStateHelper() != null
                && support.getStateHelper().getStateIndex(rideState) == StateSupport.NO_STATE) {
            return;
        }
        if (support.getStateHelper() != null) {
            int stateIndex = support.getStateHelper().getStateIndex(rideState);
            if (stateIndex >= 0 && support.inState(stateIndex)) {
                return;
            }
        }
        support.setState(mountRef, rideState, null, commandBuffer);
    }

    private void clearStaleRideState(Ref<EntityStore> riderRef,
                                     Ref<EntityStore> mountRef,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (riderRef == null || !riderRef.isValid()) {
            return;
        }
        commandBuffer.run(bufferStore -> {
            if (riderRef.isValid()) {
                bufferStore.tryRemoveComponent(riderRef, rideRiderComponentType);
                bufferStore.tryRemoveComponent(riderRef, mountedComponentType);
            }
            if (mountRef != null && mountRef.isValid()) {
                bufferStore.tryRemoveComponent(mountRef, rideMountComponentType);
            }
        });
    }

    private boolean matchesMountUuid(@Nonnull TameworkRideRiderComponent rider,
                                     @Nonnull Ref<EntityStore> mountRef,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        String expectedMountUuid = rider.getMountUuid();
        if (expectedMountUuid.isBlank()) {
            return true;
        }
        UUIDComponent uuid = commandBuffer.getComponent(mountRef, uuidComponentType);
        return uuid != null && expectedMountUuid.equals(uuid.getUuid().toString());
    }

    private void captureBody(@Nonnull TameworkRideMountComponent mount, Direction direction) {
        if (direction != null) {
            mount.captureBodyRotation(direction.yaw, direction.pitch, direction.roll);
        }
    }

    private void captureHead(@Nonnull TameworkRideMountComponent mount, Direction direction) {
        if (direction != null) {
            mount.captureHeadRotation(direction.yaw, direction.pitch, direction.roll);
        }
    }

    private void captureStates(@Nonnull TameworkRideMountComponent mount, MovementStates states) {
        mount.captureRiderMovementStates(states);
    }

    private void captureAbsoluteMovement(@Nonnull TameworkRideMountComponent mount,
                                         @Nonnull Ref<EntityStore> mountRef,
                                         @Nonnull PlayerInput.AbsoluteMovement absolute,
                                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        TransformComponent transform = commandBuffer.getComponent(mountRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d position = transform.getPosition();
        captureWorldMovement(
                mount,
                absolute.getX() - position.x,
                absolute.getY() - position.y,
                absolute.getZ() - position.z
        );
    }

    private void captureVelocityFallback(@Nonnull TameworkRideMountComponent mount,
                                         @Nonnull PlayerInput.SetClientVelocity velocity) {
        if (mount.hasWishMovement() || velocity.getVelocity() == null) {
            return;
        }
        Vector3d value = velocity.getVelocity();
        captureWorldMovement(mount, value.x, value.y, value.z);
    }

    private void captureWorldMovement(@Nonnull TameworkRideMountComponent mount,
                                      double worldX,
                                      double worldY,
                                      double worldZ) {
        captureWorldMovement(mount, worldX, worldY, worldZ, false);
    }

    private void captureWorldMovement(@Nonnull TameworkRideMountComponent mount,
                                      double worldX,
                                      double worldY,
                                      double worldZ,
                                      boolean normalizeIntent) {
        double horizontalLength = Math.sqrt(worldX * worldX + worldZ * worldZ);
        double vertical = normalizeIntent ? clamp(worldY * FALLBACK_VERTICAL_SCALE, -1.0, 1.0) : clamp(worldY, -1.0, 1.0);
        if (horizontalLength <= 0.0001 && Math.abs(vertical) <= 0.0001) {
            return;
        }

        double normalizedX;
        double normalizedZ;
        if (normalizeIntent && horizontalLength > 0.0001) {
            normalizedX = worldX / horizontalLength;
            normalizedZ = worldZ / horizontalLength;
        } else {
            normalizedX = horizontalLength > 1.0 ? worldX / horizontalLength : worldX;
            normalizedZ = horizontalLength > 1.0 ? worldZ / horizontalLength : worldZ;
        }
        double yaw = mount.hasHeadRotation()
                ? mount.getHeadYaw()
                : mount.hasBodyRotation() ? mount.getBodyYaw() : 0.0;
        double forwardX = -Math.sin(yaw);
        double forwardZ = -Math.cos(yaw);
        double rightX = -Math.sin(yaw - Math.PI / 2.0);
        double rightZ = -Math.cos(yaw - Math.PI / 2.0);
        double strafe = clamp(normalizedX * rightX + normalizedZ * rightZ, -1.0, 1.0);
        double forward = clamp(normalizedX * forwardX + normalizedZ * forwardZ, -1.0, 1.0);
        mount.captureWishMovement(strafe, vertical, forward);
    }

    private void captureMountTurnAsStrafe(@Nonnull TameworkRideMountComponent mount, float yawDelta) {
        if (Math.abs(yawDelta) <= MOUNT_TURN_STRAFE_DEAD_ZONE) {
            return;
        }
        double existingStrafe = mount.hasWishMovement() ? mount.getWishX() : 0.0;
        double existingVertical = mount.hasWishMovement() ? mount.getWishY() : 0.0;
        double existingForward = mount.hasWishMovement() ? mount.getWishZ() : 0.0;
        double strafe = clamp(existingStrafe - yawDelta * MOUNT_TURN_STRAFE_SCALE, -1.0, 1.0);
        mount.captureWishMovement(strafe, existingVertical, existingForward);
    }

    private void applyRiderLocalInput(@Nonnull PlayerInput.InputUpdate inputUpdate,
                                      int index,
                                      @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                                      @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (inputUpdate instanceof PlayerInput.SetBody
                || inputUpdate instanceof PlayerInput.SetHead
                || inputUpdate instanceof PlayerInput.SetMovementStates) {
            inputUpdate.apply(commandBuffer, archetypeChunk, index);
            return;
        }
        if (inputUpdate instanceof PlayerInput.SetRiderMovementStates riderStates) {
            MovementStatesComponent movementStates =
                    archetypeChunk.getComponent(index, movementStatesComponentType);
            if (movementStates != null) {
                movementStates.setMovementStates(riderStates.movementStates());
            }
        }
    }

    private void captureCurrentRiderRotation(@Nonnull TameworkRideMountComponent mount,
                                             int index,
                                             @Nonnull ArchetypeChunk<EntityStore> archetypeChunk) {
        TransformComponent transform =
                archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (transform != null && transform.getRotation() != null) {
            mount.captureBodyRotation(
                    transform.getRotation().getYaw(),
                    transform.getRotation().getPitch(),
                    transform.getRotation().getRoll()
            );
        }
        HeadRotation headRotation =
                archetypeChunk.getComponent(index, HeadRotation.getComponentType());
        if (headRotation != null && headRotation.getRotation() != null) {
            mount.captureHeadRotation(
                    headRotation.getRotation().getYaw(),
                    headRotation.getRotation().getPitch(),
                    headRotation.getRotation().getRoll()
            );
        }
    }

    private String summarizeQueue(@Nonnull List<PlayerInput.InputUpdate> queue) {
        StringBuilder summary = new StringBuilder();
        for (PlayerInput.InputUpdate inputUpdate : queue) {
            if (summary.length() > 0) {
                summary.append(',');
            }
            summary.append(inputUpdate == null ? "<null>" : inputUpdate.getClass().getSimpleName());
        }
        return summary.toString();
    }

    private void maybeLogDebug(@Nonnull TameworkRideMountComponent mount,
                               @Nonnull Ref<EntityStore> mountRef,
                               int queueSize,
                               @Nonnull String queueSummary,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDebugMs < 1000) {
            return;
        }
        lastDebugMs = now;
        String state = "<none>";
        String controller = "<none>";
        String bodyMotion = "<none>";
        NPCEntity npc = commandBuffer.getComponent(mountRef, NPCEntity.getComponentType());
        if (npc != null && npc.getRole() != null) {
            Role role = npc.getRole();
            state = role.getStateSupport() == null ? "<none>" : role.getStateSupport().getStateName();
            controller = role.getActiveMotionController() == null
                    ? "<none>"
                    : role.getActiveMotionController().getType();
            BodyMotion motion = role.getLastBodySteeringMotion();
            bodyMotion = motion == null ? "<none>" : motion.getClass().getSimpleName();
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkRide debug: inputCapture state=%s controller=%s bodyMotion=%s queueSize=%s queue=%s wish=%s/%s/%s hasWish=%s " +
                        "body=%s/%s/%s hasBody=%s head=%s/%s/%s hasHead=%s jump=%s crouch=%s flying=%s",
                state,
                controller,
                bodyMotion,
                queueSize,
                queueSummary,
                mount.getWishX(),
                mount.getWishY(),
                mount.getWishZ(),
                mount.hasWishMovement(),
                mount.getBodyYaw(),
                mount.getBodyPitch(),
                mount.getBodyRoll(),
                mount.hasBodyRotation(),
                mount.getHeadYaw(),
                mount.getHeadPitch(),
                mount.getHeadRoll(),
                mount.hasHeadRotation(),
                mount.isRiderJumping(),
                mount.isRiderCrouching(),
                mount.isRiderFlying()
        );
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float normalizeAngle(float angle) {
        while (angle > Math.PI) {
            angle -= (float) (Math.PI * 2.0);
        }
        while (angle < -Math.PI) {
            angle += (float) (Math.PI * 2.0);
        }
        return angle;
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
