package com.alechilles.alecstamework.npc.network;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.npc.movement.TameworkRideVelocityIntent;
import com.alechilles.alecstamework.npc.systems.MountedRideClientAttachment;
import com.alechilles.alecstamework.npc.systems.MountedRideInputProbeLogger;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.Vector2i;
import com.hypixel.hytale.protocol.packets.entities.MountMovement;
import com.hypixel.hytale.protocol.packets.interaction.DismountNPC;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.protocol.packets.player.MouseInteraction;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.SubPacketHandler;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles NPC dismount packets for Tamework rides before vanilla attempts the NPCMountComponent path.
 */
public final class MountedRidePacketHandler implements SubPacketHandler {
    private static final ConcurrentMap<UUID, RideSession> ACTIVE_TAMEWORK_RIDES = new ConcurrentHashMap<>();
    private static final float MOUSE_LOOK_RADIANS_PER_UNIT = 0.0025f;
    private static final float MIN_CAMERA_PITCH = (float) -Math.toRadians(85.0);
    private static final float MAX_CAMERA_PITCH = (float) Math.toRadians(85.0);
    private static final double MAX_VELOCITY_LOOK_YAW_DELTA = Math.toRadians(25.0);
    private static final double VELOCITY_LOOK_FORWARD_DEAD_ZONE = 0.25;
    private static final double VELOCITY_LOOK_FORWARD_DOMINANCE = 1.35;
    private static final double VELOCITY_BRAKE_BACKWARD_DEAD_ZONE = -0.25;
    private static final double VELOCITY_BRAKE_BACKWARD_DOMINANCE = 1.35;

    private final IPacketHandler packetHandler;
    private Consumer<ToServerPacket> clientMovementDelegate;
    private Consumer<ToServerPacket> mountMovementDelegate;
    private Consumer<ToServerPacket> mouseInteractionDelegate;
    private long lastClientMovementDebugMs;
    private long lastMountMovementDebugMs;
    private long lastMouseMovementDebugMs;

    public MountedRidePacketHandler(@Nonnull IPacketHandler packetHandler) {
        this.packetHandler = packetHandler;
    }

    public static void registerRide(@Nonnull UUID riderEntityUuid, @Nonnull UUID mountUuid, @Nonnull World world) {
        registerRide(null, riderEntityUuid, mountUuid, world);
    }

    public static void registerRide(@Nullable UUID playerUuid,
                                    @Nonnull UUID riderEntityUuid,
                                    @Nonnull UUID mountUuid,
                                    @Nonnull World world) {
        RideSession session = new RideSession(riderEntityUuid, mountUuid, world);
        ACTIVE_TAMEWORK_RIDES.put(riderEntityUuid, session);
        if (playerUuid != null) {
            ACTIVE_TAMEWORK_RIDES.put(playerUuid, session);
        }
    }

    public static void unregisterRide(@Nonnull UUID riderUuid) {
        ACTIVE_TAMEWORK_RIDES.remove(riderUuid);
        ACTIVE_TAMEWORK_RIDES.forEach((key, session) -> {
            if (session.riderEntityUuid.equals(riderUuid)) {
                ACTIVE_TAMEWORK_RIDES.remove(key, session);
            }
        });
    }

    public static void unregisterRide(@Nullable String riderUuid) {
        if (riderUuid == null || riderUuid.isBlank()) {
            return;
        }
        try {
            unregisterRide(UUID.fromString(riderUuid));
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void registerHandlers() {
        clientMovementDelegate = findRegisteredHandler(ClientMovement.PACKET_ID);
        mountMovementDelegate = findRegisteredHandler(MountMovement.PACKET_ID);
        mouseInteractionDelegate = findRegisteredHandler(MouseInteraction.PACKET_ID);
        packetHandler.registerHandler(DismountNPC.PACKET_ID, packet -> handle((DismountNPC) packet));
        packetHandler.registerHandler(ClientMovement.PACKET_ID, packet -> handleClientMovement((ClientMovement) packet));
        packetHandler.registerHandler(MountMovement.PACKET_ID, packet -> handleMountMovement((MountMovement) packet));
        packetHandler.registerHandler(MouseInteraction.PACKET_ID, packet -> handleMouseInteraction((MouseInteraction) packet));
    }

    private void handleClientMovement(@Nonnull ClientMovement packet) {
        if (!tryHandleTameworkClientMovement(packet)) {
            delegate(clientMovementDelegate, packet);
        }
    }

    private void handleMountMovement(@Nonnull MountMovement packet) {
        if (!tryHandleTameworkMountMovement(packet)) {
            delegate(mountMovementDelegate, packet);
        }
    }

    private void handleMouseInteraction(@Nonnull MouseInteraction packet) {
        tryHandleTameworkMouseInteraction(packet);
        delegate(mouseInteractionDelegate, packet);
    }

    private boolean tryHandleTameworkClientMovement(@Nonnull ClientMovement packet) {
        RideSession session = resolveRegisteredRideSession();
        if (session == null) {
            return false;
        }
        session.world.execute(() -> {
            RideContext current = resolveRideContext(session);
            if (current == null) {
                unregisterRide(session.riderEntityUuid);
                return;
            }
            TameworkRideMountComponent mount = current.store.getComponent(current.mountRef, current.mountType);
            if (mount == null) {
                return;
            }
            boolean capturedMovementIntent = false;
            boolean capturedPacketInput = false;
            captureStates(mount, packet.riderMovementStates != null ? packet.riderMovementStates : packet.movementStates);
            capturedPacketInput |= packet.riderMovementStates != null || packet.movementStates != null;
            if (packet.bodyOrientation != null) {
                mount.captureBodyRotation(packet.bodyOrientation.yaw, packet.bodyOrientation.pitch, packet.bodyOrientation.roll);
                capturedPacketInput = true;
            }
            if (packet.lookOrientation != null) {
                mount.captureHeadRotation(packet.lookOrientation.yaw, packet.lookOrientation.pitch, packet.lookOrientation.roll);
                capturedPacketInput = true;
            }
            if (packet.wishMovement != null) {
                captureWish(mount, packet.wishMovement.x, packet.wishMovement.y, packet.wishMovement.z);
                capturedMovementIntent = true;
                capturedPacketInput = true;
            } else if (packet.velocity != null) {
                captureVelocityMovementIntent(mount, packet.velocity.x, packet.velocity.y, packet.velocity.z);
                capturedMovementIntent = true;
                capturedPacketInput = true;
            }
            if (capturedPacketInput) {
                mount.setLastInputAtMs(System.currentTimeMillis());
            }
            MountedRideInputProbeLogger.logClientMovement(
                    mount,
                    packet.wishMovement,
                    packet.velocity,
                    packet.movementStates,
                    packet.riderMovementStates,
                    capturedMovementIntent
            );
            logClientMovementDebug(packet, mount, capturedMovementIntent);
            current.store.putComponent(current.mountRef, current.mountType, mount);
        });
        return true;
    }

    private boolean tryHandleTameworkMountMovement(@Nonnull MountMovement packet) {
        RideSession session = resolveRegisteredRideSession();
        if (session == null) {
            return false;
        }
        session.world.execute(() -> {
            RideContext current = resolveRideContext(session);
            if (current == null) {
                unregisterRide(session.riderEntityUuid);
                return;
            }
            TameworkRideMountComponent mount = current.store.getComponent(current.mountRef, current.mountType);
            if (mount == null) {
                return;
            }
            if (packet.bodyOrientation != null && !mount.hasHeadRotation()) {
                mount.captureBodyRotation(packet.bodyOrientation.yaw, packet.bodyOrientation.pitch, packet.bodyOrientation.roll);
            }
            captureStates(mount, packet.movementStates);
            if (!mount.hasWishMovement() && packet.absolutePosition != null) {
                captureAbsoluteMovementFromMount(current.mountRef, current.store, mount, packet.absolutePosition);
            }
            mount.setLastInputAtMs(System.currentTimeMillis());
            logMountMovementDebug(packet, mount);
            current.store.putComponent(current.mountRef, current.mountType, mount);
        });
        return true;
    }

    private boolean tryHandleTameworkMouseInteraction(@Nonnull MouseInteraction packet) {
        RideSession session = resolveRegisteredRideSession();
        if (session == null || packet.mouseMotion == null || packet.mouseMotion.relativeMotion == null) {
            return false;
        }
        session.world.execute(() -> {
            RideContext current = resolveRideContext(session);
            if (current == null) {
                unregisterRide(session.riderEntityUuid);
                return;
            }
            TameworkRideMountComponent mount = current.store.getComponent(current.mountRef, current.mountType);
            if (mount == null) {
                return;
            }
            captureMouseLook(mount, packet.mouseMotion.relativeMotion.x, packet.mouseMotion.relativeMotion.y);
            mount.setLastInputAtMs(System.currentTimeMillis());
            logMouseMovementDebug(packet, mount);
            current.store.putComponent(current.mountRef, current.mountType, mount);
        });
        return true;
    }

    private void handle(@Nonnull DismountNPC packet) {
        PlayerRef playerRef = packetHandler.getPlayerRef();
        Ref<EntityStore> riderRef = playerRef.getReference();
        if (riderRef == null || !riderRef.isValid()) {
            return;
        }
        Store<EntityStore> store = riderRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> handleOnWorldThread(riderRef, store));
    }

    private void handleOnWorldThread(@Nonnull Ref<EntityStore> riderRef, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(riderRef, Player.getComponentType());
        if (player == null) {
            return;
        }
        Tamework instance = Tamework.getInstance();
        ComponentType<EntityStore, TameworkRideRiderComponent> riderType =
                instance == null ? null : instance.getRideRiderComponentType();
        ComponentType<EntityStore, TameworkRideMountComponent> mountType =
                instance == null ? null : instance.getRideMountComponentType();
        TameworkRideRiderComponent rider = riderType == null ? null : store.getComponent(riderRef, riderType);
        Ref<EntityStore> mountRef = rider == null ? null : resolveMountRef(rider, store);
        TameworkRideMountComponent mount = mountRef == null || !mountRef.isValid() || mountType == null
                ? null
                : store.getComponent(mountRef, mountType);
        if (mount != null) {
            logDebug(
                    "TameworkRide debug: dismountPacket riderUuid=%s mountUuid=%s",
                    mount.getRiderUuid(),
                    rider == null ? "<none>" : rider.getMountUuid()
            );
            unregisterRide(mount.getRiderUuid());
            UUID playerUuid = player.getUuid();
            if (playerUuid != null) {
                unregisterRide(playerUuid);
            }
            MountedRideClientAttachment.placeRiderAtMountAnchor(store, riderRef, mountRef, mount);
            MountedRideClientAttachment.detach(store, riderRef);
            PlayerInput playerInput = store.getComponent(riderRef, PlayerInput.getComponentType());
            if (playerInput != null) {
                playerInput.getMovementUpdateQueue().clear();
            }
            restoreNpcState(mountRef, mount, store);
            if (riderType != null) {
                store.tryRemoveComponent(riderRef, riderType);
            }
            store.tryRemoveComponent(mountRef, mountType);
            store.tryRemoveComponent(riderRef, MountedComponent.getComponentType());
            return;
        }
        if (handleMountedGlideDismount(riderRef, store, instance)) {
            return;
        }
        handleVanillaDismount(riderRef, store, player);
    }

    private boolean handleMountedGlideDismount(@Nonnull Ref<EntityStore> riderRef,
                                               @Nonnull Store<EntityStore> store,
                                               @Nullable Tamework instance) {
        ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderType =
                instance == null ? null : instance.getMountedGlideRiderComponentType();
        ComponentType<EntityStore, TameworkMountedGlideComponent> mountType =
                instance == null ? null : instance.getMountedGlideComponentType();
        TameworkMountedGlideRiderComponent rider = riderType == null ? null : store.getComponent(riderRef, riderType);
        if (rider == null) {
            return false;
        }
        Ref<EntityStore> mountRef = resolveMountedGlideMountRef(rider, store);
        TameworkMountedGlideComponent mount = mountRef == null || !mountRef.isValid() || mountType == null
                ? null
                : store.getComponent(mountRef, mountType);
        logDebug(
                "TameworkGlide debug: dismountPacket riderUuid=%s mountUuid=%s mountedGlide=%s",
                riderRef,
                rider.getMountUuid(),
                mount != null
        );
        Player player = store.getComponent(riderRef, Player.getComponentType());
        if (player != null) {
            player.setMountEntityId(0);
            MountPlugin.resetOriginalPlayerMovementSettings(riderRef, store);
        }
        if (mountRef != null && mountRef.isValid()) {
            removeNativeMountComponent(mountRef, store);
            if (mount != null) {
                restoreNpcState(mountRef, mount, store);
            }
            store.ensureAndGetComponent(mountRef, Interactable.getComponentType());
            if (mountType != null) {
                store.tryRemoveComponent(mountRef, mountType);
            }
        }
        if (riderType != null) {
            store.tryRemoveComponent(riderRef, riderType);
        }
        return true;
    }

    private void removeNativeMountComponent(@Nonnull Ref<EntityStore> mountRef,
                                            @Nonnull Store<EntityStore> store) {
        NPCMountComponent nativeMount = store.getComponent(mountRef, NPCMountComponent.getComponentType());
        if (nativeMount != null) {
            nativeMount.setOwnerPlayerRef(null);
            store.tryRemoveComponent(mountRef, NPCMountComponent.getComponentType());
        }
    }

    @Nullable
    private RideSession resolveRegisteredRideSession() {
        PlayerRef playerRef = packetHandler.getPlayerRef();
        if (playerRef == null) {
            return null;
        }
        UUID playerUuid = playerRef.getUuid();
        return playerUuid == null ? null : ACTIVE_TAMEWORK_RIDES.get(playerUuid);
    }

    @Nullable
    private RideContext resolveRideContext(@Nonnull RideSession session) {
        Ref<EntityStore> riderRef = session.world.getEntityRef(session.riderEntityUuid);
        Ref<EntityStore> mountRef = session.world.getEntityRef(session.mountUuid);
        if (riderRef == null || mountRef == null || !riderRef.isValid() || !mountRef.isValid()) {
            return null;
        }
        Store<EntityStore> store = riderRef.getStore();
        Tamework instance = Tamework.getInstance();
        ComponentType<EntityStore, TameworkRideRiderComponent> riderType =
                instance == null ? null : instance.getRideRiderComponentType();
        ComponentType<EntityStore, TameworkRideMountComponent> mountType =
                instance == null ? null : instance.getRideMountComponentType();
        if (riderType == null || mountType == null) {
            return null;
        }
        TameworkRideRiderComponent rider = store.getComponent(riderRef, riderType);
        if (rider == null) {
            return null;
        }
        if (!session.mountUuid.toString().equals(rider.getMountUuid())) {
            return null;
        }
        if (store.getComponent(mountRef, mountType) == null) {
            return null;
        }
        return new RideContext(
                riderRef,
                mountRef,
                store,
                store.getExternalData().getWorld(),
                mountType
        );
    }

    private void captureStates(@Nonnull TameworkRideMountComponent mount, @Nullable MovementStates states) {
        mount.captureRiderMovementStates(states);
    }

    private void captureWish(@Nonnull TameworkRideMountComponent mount,
                             double wishX,
                             double wishY,
                             double wishZ) {
        double length = Math.sqrt(wishX * wishX + wishZ * wishZ);
        if (length <= 0.0001) {
            mount.clearWishMovement();
            return;
        }
        double scale = length > 1.0 ? 1.0 / length : 1.0;
        mount.captureWishMovement(wishX * scale, 0.0, wishZ * scale, wishZ < -0.0001);
    }

    private void captureAbsoluteMovementFromMount(@Nonnull Ref<EntityStore> mountRef,
                                                  @Nonnull Store<EntityStore> store,
                                                  @Nonnull TameworkRideMountComponent mount,
                                                  @Nonnull Position absolutePosition) {
        TransformComponent transform = store.getComponent(mountRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        captureWorldMovement(
                mount,
                absolutePosition.x - transform.getPosition().x,
                absolutePosition.y - transform.getPosition().y,
                absolutePosition.z - transform.getPosition().z,
                true
        );
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

    private void captureVelocityMovementIntent(@Nonnull TameworkRideMountComponent mount,
                                               double worldX,
                                               double worldY,
                                               double worldZ) {
        if (TameworkRideVelocityIntent.isVerticalDominant(worldX, worldY, worldZ)) {
            captureVerticalVelocityIntent(mount, worldX, worldY, worldZ);
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
            captureExistingForwardIntent(mount);
            return;
        }

        if (!isForwardDominant(intent)) {
            captureProjectedIntent(mount, intent);
            return;
        }

        if (captureForwardLookFromWorldVector(mount, worldX, worldY, worldZ)) {
            MovementIntent updatedIntent = projectWorldMovement(mount, worldX, worldZ, true);
            if (updatedIntent != null) {
                intent = updatedIntent;
            }
        }
        captureProjectedIntent(mount, intent);
    }

    private void captureProjectedIntent(@Nonnull TameworkRideMountComponent mount,
                                        @Nonnull MovementIntent intent) {
        double strafe = intent.strafe();
        double forward = intent.forward();
        mount.captureWishMovement(strafe, 0.0, forward);
    }

    private void captureBackwardBrakeIntent(@Nonnull TameworkRideMountComponent mount) {
        mount.captureWishMovement(0.0, 0.0, 0.0, true);
    }

    private void captureVerticalVelocityIntent(@Nonnull TameworkRideMountComponent mount,
                                               double worldX,
                                               double worldY,
                                               double worldZ) {
        mount.captureWishMovement(0.0, TameworkRideVelocityIntent.verticalInput(worldX, worldY, worldZ), 0.0);
    }

    private void captureExistingForwardIntent(@Nonnull TameworkRideMountComponent mount) {
        mount.captureWishMovement(0.0, mount.getWishY(), Math.copySign(1.0, mount.getWishZ()));
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

    private boolean isForwardDominant(@Nonnull MovementIntent intent) {
        return intent.forward() > VELOCITY_LOOK_FORWARD_DEAD_ZONE
                && Math.abs(intent.forward()) >= Math.abs(intent.strafe()) * VELOCITY_LOOK_FORWARD_DOMINANCE;
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

    private void captureMouseLook(@Nonnull TameworkRideMountComponent mount, int relativeX, int relativeY) {
        if (relativeX == 0 && relativeY == 0) {
            return;
        }
        float yaw = mount.hasHeadRotation()
                ? mount.getHeadYaw()
                : mount.hasBodyRotation() ? mount.getBodyYaw() : mount.getAuthoritativeYaw();
        float pitch = mount.hasHeadRotation()
                ? mount.getHeadPitch()
                : mount.hasBodyRotation() ? mount.getBodyPitch() : mount.getAuthoritativePitch();
        yaw = normalizeAngle(yaw - relativeX * MOUSE_LOOK_RADIANS_PER_UNIT);
        pitch = clampFloat(pitch - relativeY * MOUSE_LOOK_RADIANS_PER_UNIT, MIN_CAMERA_PITCH, MAX_CAMERA_PITCH);
        mount.captureHeadRotation(yaw, pitch, 0.0f);
    }

    private boolean captureForwardLookFromWorldVector(@Nonnull TameworkRideMountComponent mount,
                                                      double worldX,
                                                      double worldY,
                                                      double worldZ) {
        double horizontalLength = Math.sqrt(worldX * worldX + worldZ * worldZ);
        double length = Math.sqrt(horizontalLength * horizontalLength + worldY * worldY);
        if (length <= 0.05 || horizontalLength <= 0.0001) {
            return false;
        }
        float yaw = normalizeAngle((float) Math.atan2(-worldX, -worldZ));
        float pitch = clampFloat((float) -Math.atan2(worldY, horizontalLength), MIN_CAMERA_PITCH, MAX_CAMERA_PITCH);
        if (mount.hasHeadRotation() || mount.hasBodyRotation() || mount.hasAuthoritativePose()) {
            float currentYaw = mount.hasHeadRotation()
                    ? mount.getHeadYaw()
                    : mount.hasBodyRotation() ? mount.getBodyYaw() : mount.getAuthoritativeYaw();
            double yawDelta = Math.abs(normalizeAngle(yaw - currentYaw));
            if (yawDelta > MAX_VELOCITY_LOOK_YAW_DELTA) {
                return false;
            }
        }
        mount.captureHeadRotation(yaw, pitch, 0.0f);
        return true;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private Consumer<ToServerPacket> findRegisteredHandler(int packetId) {
        Class<?> current = packetHandler.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField("handlers");
                field.setAccessible(true);
                Consumer<ToServerPacket>[] handlers = (Consumer<ToServerPacket>[]) field.get(packetHandler);
                return handlers != null && packetId >= 0 && packetId < handlers.length ? handlers[packetId] : null;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        return null;
    }

    private void delegate(@Nullable Consumer<ToServerPacket> delegate, @Nonnull ToServerPacket packet) {
        if (delegate != null) {
            delegate.accept(packet);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float normalizeAngle(float angle) {
        while (angle <= -Math.PI) {
            angle += Math.PI * 2.0f;
        }
        while (angle > Math.PI) {
            angle -= Math.PI * 2.0f;
        }
        return angle;
    }

    private void handleVanillaDismount(@Nonnull Ref<EntityStore> riderRef,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull Player player) {
        MountedComponent mounted = store.getComponent(riderRef, MountedComponent.getComponentType());
        if (mounted == null) {
            MountPlugin.checkDismountNpc(store, riderRef, player);
            return;
        }
        if (mounted.getControllerType() == MountController.BlockMount) {
            store.tryRemoveComponent(riderRef, MountedComponent.getComponentType());
        }
    }

    private void logDebug(@Nonnull String message, Object... args) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(String.format(message, args));
    }

    private void logClientMovementDebug(@Nonnull ClientMovement packet,
                                        @Nonnull TameworkRideMountComponent mount,
                                        boolean capturedMovementIntent) {
        long now = System.currentTimeMillis();
        if (now - lastClientMovementDebugMs < 1000) {
            return;
        }
        lastClientMovementDebugMs = now;
        logDebug(
                "TameworkRide debug: packet source=clientMovement mountedTo=%s capturedMovement=%s " +
                        "packetWish=%s packetVelocity=%s relative=%s absolute=%s body=%s look=%s " +
                        "movementStates=%s riderStates=%s snapshotWish=%s/%s/%s hasWish=%s " +
                        "snapshotBody=%s/%s hasBody=%s snapshotHead=%s/%s hasHead=%s riderJump=%s riderCrouch=%s",
                packet.mountedTo,
                capturedMovementIntent,
                formatPosition(packet.wishMovement),
                formatVector(packet.velocity),
                packet.relativePosition != null,
                formatPosition(packet.absolutePosition),
                formatDirection(packet.bodyOrientation),
                formatDirection(packet.lookOrientation),
                formatStates(packet.movementStates),
                formatStates(packet.riderMovementStates),
                mount.getWishX(),
                mount.getWishY(),
                mount.getWishZ(),
                mount.hasWishMovement(),
                mount.getBodyYaw(),
                mount.getBodyPitch(),
                mount.hasBodyRotation(),
                mount.getHeadYaw(),
                mount.getHeadPitch(),
                mount.hasHeadRotation(),
                mount.isRiderJumping(),
                mount.isRiderCrouching()
        );
    }

    private void logMountMovementDebug(@Nonnull MountMovement packet, @Nonnull TameworkRideMountComponent mount) {
        long now = System.currentTimeMillis();
        if (now - lastMountMovementDebugMs < 1000) {
            return;
        }
        lastMountMovementDebugMs = now;
        logDebug(
                "TameworkRide debug: packet source=mountMovement absolute=%s body=%s movementStates=%s " +
                        "snapshotWish=%s/%s/%s hasWish=%s snapshotBody=%s/%s hasBody=%s snapshotHead=%s/%s hasHead=%s",
                formatPosition(packet.absolutePosition),
                formatDirection(packet.bodyOrientation),
                formatStates(packet.movementStates),
                mount.getWishX(),
                mount.getWishY(),
                mount.getWishZ(),
                mount.hasWishMovement(),
                mount.getBodyYaw(),
                mount.getBodyPitch(),
                mount.hasBodyRotation(),
                mount.getHeadYaw(),
                mount.getHeadPitch(),
                mount.hasHeadRotation()
        );
    }

    private void logMouseMovementDebug(@Nonnull MouseInteraction packet, @Nonnull TameworkRideMountComponent mount) {
        long now = System.currentTimeMillis();
        if (now - lastMouseMovementDebugMs < 1000) {
            return;
        }
        lastMouseMovementDebugMs = now;
        Vector2i relativeMotion = packet.mouseMotion == null ? null : packet.mouseMotion.relativeMotion;
        logDebug(
                "TameworkRide debug: packet source=mouseInteraction motion=%s snapshotHead=%s/%s hasHead=%s",
                formatVector(relativeMotion),
                mount.getHeadYaw(),
                mount.getHeadPitch(),
                mount.hasHeadRotation()
        );
    }

    @Nonnull
    private String formatPosition(@Nullable Position position) {
        return position == null ? "<none>" : position.x + "/" + position.y + "/" + position.z;
    }

    @Nonnull
    private String formatVector(@Nullable com.hypixel.hytale.protocol.Vector3d vector) {
        return vector == null ? "<none>" : vector.x + "/" + vector.y + "/" + vector.z;
    }

    @Nonnull
    private String formatVector(@Nullable Vector2i vector) {
        return vector == null ? "<none>" : vector.x + "/" + vector.y;
    }

    @Nonnull
    private String formatDirection(@Nullable com.hypixel.hytale.protocol.Direction direction) {
        return direction == null ? "<none>" : direction.yaw + "/" + direction.pitch + "/" + direction.roll;
    }

    @Nonnull
    private String formatStates(@Nullable MovementStates states) {
        if (states == null) {
            return "<none>";
        }
        return "jump=" + states.jumping +
                ",crouch=" + states.crouching +
                ",fly=" + states.flying +
                ",sprint=" + states.sprinting +
                ",ground=" + states.onGround +
                ",run=" + states.running +
                ",mounting=" + states.mounting;
    }

    private void restoreNpcState(@Nonnull Ref<EntityStore> mountRef,
                                 @Nonnull TameworkRideMountComponent mount,
                                 @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(mountRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        npc.playAnimation(mountRef, AnimationSlot.Movement, null, store);
        Role role = npc.getRole();
        if (!mount.getPreviousMotionController().isBlank()) {
            role.setActiveMotionController(mountRef, npc, mount.getPreviousMotionController(), store);
        }
        applyState(role, mountRef, store, mount.getPreviousState(), mount.getPreviousSubState());
    }

    private void restoreNpcState(@Nonnull Ref<EntityStore> mountRef,
                                 @Nonnull TameworkMountedGlideComponent mount,
                                 @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(mountRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        npc.playAnimation(mountRef, AnimationSlot.Movement, null, store);
        Role role = npc.getRole();
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

    @Nullable
    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkRideRiderComponent rider,
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

    @Nullable
    private Ref<EntityStore> resolveMountedGlideMountRef(@Nonnull TameworkMountedGlideRiderComponent rider,
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

    private record RideContext(
            Ref<EntityStore> riderRef,
            Ref<EntityStore> mountRef,
            Store<EntityStore> store,
            World world,
            ComponentType<EntityStore, TameworkRideMountComponent> mountType
    ) {
    }

    private record RideSession(UUID riderEntityUuid, UUID mountUuid, World world) {
    }

    private record MovementIntent(double strafe, double forward) {
    }
}
