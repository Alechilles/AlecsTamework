package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ApplyLookType;
import com.hypixel.hytale.protocol.ApplyMovementType;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.CanMoveType;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.ComponentUpdateType;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import org.joml.Vector2f;
import org.joml.Vector3f;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.protocol.packets.interaction.DismountNPC;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class MountedRideClientAttachment {
    public static final double DEFAULT_RIDE_INPUT_SPEED_MODIFIER = 10.0;
    private static final double RIDE_CAMERA_EYE_HEIGHT = 1.6;

    private MountedRideClientAttachment() {
    }

    public static boolean attach(@Nonnull Store<EntityStore> store,
                                 @Nonnull Ref<EntityStore> riderRef,
                                 @Nonnull Ref<EntityStore> mountRef,
                                 @Nonnull TameworkRideMountComponent mount) {
        return attach(store, riderRef, mountRef, mount, DEFAULT_RIDE_INPUT_SPEED_MODIFIER);
    }

    public static boolean attach(@Nonnull Store<EntityStore> store,
                                 @Nonnull Ref<EntityStore> riderRef,
                                 @Nonnull Ref<EntityStore> mountRef,
                                 @Nonnull TameworkRideMountComponent mount,
                                 double speedModifier) {
        // This proof path deliberately avoids all native mount identity. MountNPC/player.mountEntityId
        // re-enter the client ground-mount controller and override Tamework flight.
        PlayerInput playerInput = store.getComponent(riderRef, PlayerInput.getComponentType());
        if (playerInput != null) {
            playerInput.setMountId(0);
        }
        Player player = store.getComponent(riderRef, Player.getComponentType());
        if (player == null) {
            return false;
        }
        NetworkId mountNetworkId = store.getComponent(mountRef, NetworkId.getComponentType());
        if (mountNetworkId == null) {
            return false;
        }
        return sendRideCamera(player, mountNetworkId.getId(), mount, speedModifier);
    }

    public static boolean updateCamera(@Nonnull Store<EntityStore> store,
                                       @Nonnull Ref<EntityStore> riderRef,
                                       double speedModifier) {
        Player player = store.getComponent(riderRef, Player.getComponentType());
        TameworkRideRiderComponentAccess riderMount = resolveRideMount(store, riderRef);
        if (player == null || riderMount == null) {
            return false;
        }
        return sendRideCamera(player, riderMount.mountNetworkId(), riderMount.mount(), speedModifier);
    }

    private static boolean sendRideCamera(@Nonnull Player player,
                                          int mountEntityId,
                                          @Nonnull TameworkRideMountComponent mount,
                                          double speedModifier) {
        if (player.getPlayerRef() == null || player.getPlayerRef().getPacketHandler() == null) {
            return false;
        }
        player.getPlayerRef().getPacketHandler().writeNoCache(new SetServerCamera(
                ClientCameraView.Custom,
                false,
                createRideCameraSettings(mountEntityId, mount, speedModifier)
        ));
        return true;
    }

    @Nonnull
    private static ServerCameraSettings createRideCameraSettings(int mountEntityId,
                                                                 @Nonnull TameworkRideMountComponent mount,
                                                                 double speedModifier) {
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 1.0f;
        settings.rotationLerpSpeed = 1.0f;
        settings.distance = 0.0f;
        settings.speedModifier = (float) Math.max(0.0, speedModifier);
        settings.allowPitchControls = true;
        settings.displayCursor = false;
        settings.displayReticle = true;
        settings.sendMouseMotion = true;
        settings.skipCharacterPhysics = true;
        settings.isFirstPerson = true;
        settings.movementForceRotationType = MovementForceRotationType.CameraRotation;
        settings.attachedToType = AttachedToType.EntityId;
        settings.attachedToEntityId = mountEntityId;
        settings.eyeOffset = false;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.None;
        settings.positionOffset = new com.hypixel.hytale.protocol.Position(
                mount.getAnchorX(),
                mount.getAnchorY() + RIDE_CAMERA_EYE_HEIGHT,
                mount.getAnchorZ()
        );
        settings.positionType = PositionType.AttachedToPlusOffset;
        settings.position = new com.hypixel.hytale.protocol.Position(0.0, 0.0, 0.0);
        settings.rotationType = RotationType.Custom;
        settings.rotation = resolveCameraRotation(mount);
        settings.canMoveType = CanMoveType.Always;
        settings.applyMovementType = ApplyMovementType.CharacterController;
        settings.applyLookType = ApplyLookType.Rotation;
        settings.lookMultiplier = new Vector2f(1.0f, 1.0f);
        settings.mouseInputType = MouseInputType.LookAtPlane;
        settings.planeNormal = new Vector3f(0.0f, 1.0f, 0.0f);
        return settings;
    }

    @Nonnull
    private static Direction resolveCameraRotation(@Nonnull TameworkRideMountComponent mount) {
        if (mount.hasHeadRotation()) {
            return new Direction(mount.getHeadYaw(), mount.getHeadPitch(), mount.getHeadRoll());
        }
        if (mount.hasBodyRotation()) {
            return new Direction(mount.getBodyYaw(), mount.getBodyPitch(), mount.getBodyRoll());
        }
        if (mount.hasAuthoritativePose()) {
            return new Direction(mount.getAuthoritativeYaw(), mount.getAuthoritativePitch(), mount.getAuthoritativeRoll());
        }
        return new Direction(0.0f, 0.0f, 0.0f);
    }

    public static void placeRiderAtMountAnchor(@Nonnull ComponentAccessor<EntityStore> accessor,
                                               @Nonnull Ref<EntityStore> riderRef,
                                               @Nonnull Ref<EntityStore> mountRef,
                                               @Nonnull TameworkRideMountComponent mount) {
        moveRiderToMountAnchor(accessor, riderRef, mountRef, mount, 0.0);
    }

    private static boolean moveRiderToMountAnchor(@Nonnull ComponentAccessor<EntityStore> accessor,
                                                  @Nonnull Ref<EntityStore> riderRef,
                                                  @Nonnull Ref<EntityStore> mountRef,
                                                  @Nonnull TameworkRideMountComponent mount,
                                                  double minimumDistanceSquared) {
        Player player = accessor.getComponent(riderRef, Player.getComponentType());
        TransformComponent riderTransform = accessor.getComponent(riderRef, TransformComponent.getComponentType());
        TransformComponent mountTransform = accessor.getComponent(mountRef, TransformComponent.getComponentType());
        if (player == null || mountTransform == null) {
            return false;
        }
        Vector3d anchoredPosition = computeRiderAnchor(mountTransform, mount);
        if (riderTransform != null && distanceSquared(riderTransform.getPosition(), anchoredPosition) <= minimumDistanceSquared) {
            return false;
        }
        player.moveTo(
                riderRef,
                anchoredPosition.x,
                anchoredPosition.y,
                anchoredPosition.z,
                accessor
        );
        return true;
    }

    @Nonnull
    private static Vector3d computeRiderAnchor(@Nonnull TransformComponent mountTransform,
                                               @Nonnull TameworkRideMountComponent mount) {
        Vector3d mountPosition = mountTransform.getPosition();
        var mountRotation = mountTransform.getRotation();
        double yaw = mountRotation == null ? 0.0 : mountRotation.yaw();
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double worldX = mount.getAnchorX() * cos - mount.getAnchorZ() * sin;
        double worldZ = mount.getAnchorX() * sin + mount.getAnchorZ() * cos;
        return new Vector3d(
                mountPosition.x + worldX,
                mountPosition.y + mount.getAnchorY(),
                mountPosition.z + worldZ
        );
    }

    private static double distanceSquared(@Nonnull Vector3d first, @Nonnull Vector3d second) {
        double dx = first.x - second.x;
        double dy = first.y - second.y;
        double dz = first.z - second.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public static void detach(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> riderRef) {
        if (riderRef == null || !riderRef.isValid()) {
            return;
        }
        PlayerInput playerInput = store.getComponent(riderRef, PlayerInput.getComponentType());
        if (playerInput != null) {
            playerInput.setMountId(0);
        }
        Player player = store.getComponent(riderRef, Player.getComponentType());
        if (player != null && player.getPlayerRef() != null && player.getPlayerRef().getPacketHandler() != null) {
            if (player.getMountEntityId() != 0) {
                player.setMountEntityId(0);
                player.getPlayerRef().getPacketHandler().write(new DismountNPC());
            }
            player.getPlayerRef().getPacketHandler().writeNoCache(new SetServerCamera(
                    ClientCameraView.FirstPerson,
                    false,
                    null
            ));
        }

        EntityTrackerSystems.Visible visible = store.getComponent(riderRef, EntityTrackerSystems.Visible.getComponentType());
        if (visible == null) {
            return;
        }
        queueRemove(riderRef, visible.newlyVisibleTo);
        queueRemove(riderRef, visible.visibleTo);
    }

    private static void queueRemove(@Nonnull Ref<EntityStore> riderRef,
                                    @Nonnull java.util.Map<Ref<EntityStore>, EntityTrackerSystems.EntityViewer> visibleTo) {
        for (EntityTrackerSystems.EntityViewer viewer : visibleTo.values()) {
            try {
                viewer.queueRemove(riderRef, ComponentUpdateType.Mounted);
            } catch (IllegalArgumentException ignored) {
                // The viewer may already have stopped tracking the rider.
            }
        }
    }

    @Nullable
    private static TameworkRideRiderComponentAccess resolveRideMount(@Nonnull Store<EntityStore> store,
                                                                     @Nonnull Ref<EntityStore> riderRef) {
        TameworkRideRiderComponent rider = store.getComponent(riderRef, TameworkRideRiderComponent.getComponentType());
        if (rider == null || rider.getMountUuid().isBlank()) {
            return null;
        }
        Ref<EntityStore> mountRef;
        try {
            mountRef = store.getExternalData().getWorld().getEntityRef(UUID.fromString(rider.getMountUuid()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (mountRef == null || !mountRef.isValid()) {
            return null;
        }
        TameworkRideMountComponent mount =
                store.getComponent(mountRef, TameworkRideMountComponent.getComponentType());
        NetworkId networkId = store.getComponent(mountRef, NetworkId.getComponentType());
        if (mount == null || networkId == null) {
            return null;
        }
        return new TameworkRideRiderComponentAccess(mount, networkId.getId());
    }

    private record TameworkRideRiderComponentAccess(TameworkRideMountComponent mount, int mountNetworkId) {
    }

}
