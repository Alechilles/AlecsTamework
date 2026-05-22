package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.npc.movement.TameworkRideVelocityIntent;
import com.alechilles.alecstamework.npc.network.MountedRidePacketHandler;
import com.hypixel.hytale.builtin.mounts.MountSystems;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import org.joml.Vector3d;
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
import javax.annotation.Nullable;

/**
 * Captures rider input for Tamework rides before vanilla mounted input mutates the target entity.
 */
public final class MountedRideInputCaptureSystem extends EntityTickingSystem<EntityStore> {
    private static final double MOUNT_TURN_STRAFE_SCALE = 12.0;
    private static final double MOUNT_TURN_STRAFE_DEAD_ZONE = 0.001;
    private static final double VELOCITY_BRAKE_BACKWARD_DEAD_ZONE = -0.25;
    private static final double VELOCITY_BRAKE_BACKWARD_DOMINANCE = 1.35;
    private static final long PACKET_SNAPSHOT_GRACE_MS = 250L;

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
            clearStaleRideState(riderRef, null, null, commandBuffer);
            return;
        }
        TameworkRideMountComponent mount = commandBuffer.getComponent(mountRef, rideMountComponentType);
        if (mount == null || !matchesMountUuid(rider, mountRef, commandBuffer)) {
            clearStaleRideState(riderRef, mountRef, mount, commandBuffer);
            return;
        }
        playerInput.setMountId(0);
        ensureRideState(mountRef, mount, commandBuffer);
        boolean hasRecentPacketSnapshot = hasRecentPacketSnapshot(mount);
        if (!hasRecentPacketSnapshot) {
            captureCurrentRiderRotation(mount, index, archetypeChunk);
            captureCurrentRiderMovementStates(mount, index, archetypeChunk);
        }
        List<PlayerInput.InputUpdate> queue = playerInput.getMovementUpdateQueue();
        boolean capturedMountMovement = syncAuthoritativePose(mountRef, mount, commandBuffer, false);
        if (queue.isEmpty()) {
            boolean hadWishMovement = mount.hasWishMovement();
            if (!capturedMountMovement) {
                if (!hasRecentPacketSnapshot) {
                    mount.setHasWishMovement(false);
                    mount.setWishX(0.0);
                    mount.setWishY(0.0);
                    mount.setWishZ(0.0);
                }
            }
            maybeLogDebug(mount, mountRef, 0, "<empty>", commandBuffer);
            if (capturedMountMovement || hadWishMovement) {
                commandBuffer.putComponent(mountRef, rideMountComponentType, mount);
            }
            return;
        }
        String queueSummary = summarizeQueue(queue);

        mount.setHasBodyRotation(false);
        mount.setHasHeadRotation(false);
        boolean sawMovementIntent = false;
        for (PlayerInput.InputUpdate inputUpdate : queue) {
            if (inputUpdate instanceof PlayerInput.WishMovement wish) {
                captureWish(mount, wish.getX(), wish.getY(), wish.getZ());
                sawMovementIntent = true;
            } else if (inputUpdate instanceof PlayerInput.RelativeMovement relative) {
                captureWorldMovement(mount, relative.getX(), relative.getY(), relative.getZ());
                sawMovementIntent = true;
            } else if (inputUpdate instanceof PlayerInput.AbsoluteMovement absolute) {
                captureAbsoluteMovement(mount, mountRef, absolute, commandBuffer);
                sawMovementIntent = true;
            } else if (inputUpdate instanceof PlayerInput.SetClientVelocity velocity) {
                captureVelocityFallback(mount, velocity);
                sawMovementIntent = true;
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
        if (!sawMovementIntent && !hasRecentPacketSnapshot(mount)) {
            mount.clearWishMovement();
        }
        if (!hasRecentPacketSnapshot) {
            captureCurrentRiderRotation(mount, index, archetypeChunk);
        }
        if (sawMovementIntent) {
            mount.setLastInputAtMs(System.currentTimeMillis());
        }
        maybeLogDebug(mount, mountRef, queue.size(), queueSummary, commandBuffer);
        commandBuffer.putComponent(mountRef, rideMountComponentType, mount);
        queue.clear();
    }

    private boolean hasRecentPacketSnapshot(@Nonnull TameworkRideMountComponent mount) {
        return mount.getLastInputAtMs() > 0L
                && System.currentTimeMillis() - mount.getLastInputAtMs() <= PACKET_SNAPSHOT_GRACE_MS;
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
                    transform.getRotation().yaw(),
                    transform.getRotation().pitch(),
                    transform.getRotation().roll()
            );
            commandBuffer.putComponent(mountRef, rideMountComponentType, mount);
            return false;
        }
        double dx = transform.getPosition().x - mount.getAuthoritativeX();
        double dy = transform.getPosition().y - mount.getAuthoritativeY();
        double dz = transform.getPosition().z - mount.getAuthoritativeZ();
        float yawDelta = normalizeAngle(transform.getRotation().yaw() - mount.getAuthoritativeYaw());
        float pitchDelta = normalizeAngle(transform.getRotation().pitch() - mount.getAuthoritativePitch());
        float rollDelta = normalizeAngle(transform.getRotation().roll() - mount.getAuthoritativeRoll());
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
        String subState = support.getStateHelper() == null ? "" : support.getStateHelper().getDefaultSubState();
        support.setState(mountRef, rideState, subState == null ? "" : subState, commandBuffer);
    }

    private void clearStaleRideState(Ref<EntityStore> riderRef,
                                     Ref<EntityStore> mountRef,
                                     TameworkRideMountComponent mount,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (riderRef == null || !riderRef.isValid()) {
            return;
        }
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
            if (mountRef != null && mountRef.isValid()) {
                TameworkRideMountComponent currentMount = mount == null
                        ? bufferStore.getComponent(mountRef, rideMountComponentType)
                        : mount;
                if (currentMount != null) {
                    MountedRidePacketHandler.unregisterRide(currentMount.getRiderUuid());
                    NPCEntity npc = bufferStore.getComponent(mountRef, NPCEntity.getComponentType());
                    if (npc != null) {
                        restoreNpcState(mountRef, npc, currentMount, bufferStore);
                    }
                }
                bufferStore.tryRemoveComponent(mountRef, rideMountComponentType);
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

    private void captureWish(@Nonnull TameworkRideMountComponent mount,
                             double wishX,
                             double wishY,
                             double wishZ) {
        double horizontalLength = Math.sqrt(wishX * wishX + wishZ * wishZ);
        if (horizontalLength <= 0.0001) {
            mount.clearWishMovement();
            return;
        }
        double scale = horizontalLength > 1.0 ? 1.0 / horizontalLength : 1.0;
        mount.captureWishMovement(wishX * scale, 0.0, wishZ * scale, wishZ < -0.0001);
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
                absolute.getZ() - position.z,
                true
        );
    }

    private void captureVelocityFallback(@Nonnull TameworkRideMountComponent mount,
                                         @Nonnull PlayerInput.SetClientVelocity velocity) {
        if (mount.hasWishMovement() || velocity.getVelocity() == null) {
            return;
        }
        Vector3d value = velocity.getVelocity();
        captureVelocityMovement(mount, value.x, value.y, value.z);
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
        MovementIntent intent = projectWorldMovement(mount, worldX, worldZ, normalizeIntent);
        if (intent == null) {
            mount.clearWishMovement();
            return;
        }
        captureProjectedIntent(mount, intent);
    }

    private void captureVelocityMovement(@Nonnull TameworkRideMountComponent mount,
                                         double worldX,
                                         double worldY,
                                         double worldZ) {
        if (TameworkRideVelocityIntent.isVerticalDominant(worldX, worldY, worldZ)) {
            mount.captureWishMovement(0.0, TameworkRideVelocityIntent.verticalInput(worldX, worldY, worldZ), 0.0);
            return;
        }
        if (!TameworkRideVelocityIntent.hasUsableHorizontalIntent(worldX, worldZ)) {
            mount.clearWishMovement();
            return;
        }
        MovementIntent intent = projectWorldMovement(mount, worldX, worldZ, true);
        if (intent == null) {
            mount.clearWishMovement();
            return;
        }
        if (isBackwardBrakeIntent(intent)) {
            captureBackwardBrakeIntent(mount);
            return;
        }
        if (shouldPreserveExistingForwardIntent(mount, intent)) {
            mount.captureWishMovement(0.0, mount.getWishY(), Math.copySign(1.0, mount.getWishZ()));
            return;
        }
        captureProjectedIntent(mount, intent);
    }

    @Nullable
    private MovementIntent projectWorldMovement(@Nonnull TameworkRideMountComponent mount,
                                                double worldX,
                                                double worldZ,
                                                boolean normalizeIntent) {
        double horizontalLength = Math.sqrt(worldX * worldX + worldZ * worldZ);
        if (horizontalLength <= 0.0001) {
            return null;
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
        return new MovementIntent(strafe, forward);
    }

    private void captureProjectedIntent(@Nonnull TameworkRideMountComponent mount,
                                        @Nonnull MovementIntent intent) {
        mount.captureWishMovement(intent.strafe(), 0.0, intent.forward());
    }

    private void captureBackwardBrakeIntent(@Nonnull TameworkRideMountComponent mount) {
        mount.captureWishMovement(0.0, 0.0, 0.0, true);
    }

    private boolean isBackwardBrakeIntent(@Nonnull MovementIntent intent) {
        return intent.forward() < VELOCITY_BRAKE_BACKWARD_DEAD_ZONE
                && Math.abs(intent.forward()) >= Math.abs(intent.strafe()) * VELOCITY_BRAKE_BACKWARD_DOMINANCE;
    }

    private boolean shouldPreserveExistingForwardIntent(@Nonnull TameworkRideMountComponent mount,
                                                        @Nonnull MovementIntent intent) {
        if (!mount.hasWishMovement() || Math.abs(mount.getWishZ()) < 0.75) {
            return false;
        }
        return Math.abs(intent.strafe()) > 0.65 && Math.abs(intent.forward()) < 0.35;
    }

    private void captureMountTurnAsStrafe(@Nonnull TameworkRideMountComponent mount, float yawDelta) {
        if (Math.abs(yawDelta) <= MOUNT_TURN_STRAFE_DEAD_ZONE) {
            return;
        }
        double existingStrafe = mount.hasWishMovement() ? mount.getWishX() : 0.0;
        double existingVertical = mount.hasWishMovement() ? mount.getWishY() : 0.0;
        double existingForward = mount.hasWishMovement() ? mount.getWishZ() : 0.0;
        boolean existingBackwardBrakeInput = mount.isRiderBackwardBrakeInput();
        double strafe = clamp(existingStrafe - yawDelta * MOUNT_TURN_STRAFE_SCALE, -1.0, 1.0);
        mount.captureWishMovement(strafe, existingVertical, existingForward, existingBackwardBrakeInput);
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
                    transform.getRotation().yaw(),
                    transform.getRotation().pitch(),
                    transform.getRotation().roll()
            );
        }
        HeadRotation headRotation =
                archetypeChunk.getComponent(index, HeadRotation.getComponentType());
        if (headRotation != null && headRotation.getRotation() != null) {
            mount.captureHeadRotation(
                    headRotation.getRotation().yaw(),
                    headRotation.getRotation().pitch(),
                    headRotation.getRotation().roll()
            );
        }
    }

    private void captureCurrentRiderMovementStates(@Nonnull TameworkRideMountComponent mount,
                                                   int index,
                                                   @Nonnull ArchetypeChunk<EntityStore> archetypeChunk) {
        MovementStatesComponent movementStates =
                archetypeChunk.getComponent(index, movementStatesComponentType);
        if (movementStates != null) {
            mount.captureRiderMovementStates(movementStates.getMovementStates());
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
                        "body=%s/%s/%s hasBody=%s head=%s/%s/%s hasHead=%s jump=%s crouch=%s flying=%s sprinting=%s",
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
                mount.isRiderFlying(),
                mount.isRiderSprinting()
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

    private record MovementIntent(double strafe, double forward) {
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
