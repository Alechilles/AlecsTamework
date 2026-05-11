package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.ApplyMovementType;
import com.hypixel.hytale.protocol.CanMoveType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.ComponentUpdateType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.protocol.packets.interaction.DismountNPC;
import com.hypixel.hytale.protocol.packets.interaction.MountNPC;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class MountedRideClientAttachment {
    public static final double DEFAULT_RIDE_INPUT_SPEED_MODIFIER = 10.0;

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
        if (!sendMountPacket(store, riderRef, mountRef, mount)) {
            return false;
        }
        return updateCamera(store, riderRef, speedModifier);
    }

    private static boolean sendMountPacket(@Nonnull Store<EntityStore> store,
                                           @Nonnull Ref<EntityStore> riderRef,
                                           @Nonnull Ref<EntityStore> mountRef,
                                           @Nonnull TameworkRideMountComponent mount) {
        NetworkId mountNetworkId = store.getComponent(mountRef, NetworkId.getComponentType());
        if (mountNetworkId == null || mountNetworkId.getId() <= 0) {
            return false;
        }
        int mountNetworkIdValue = mountNetworkId.getId();
        PlayerInput playerInput = store.getComponent(riderRef, PlayerInput.getComponentType());
        if (playerInput != null) {
            playerInput.setMountId(0);
        }

        Player player = store.getComponent(riderRef, Player.getComponentType());
        if (player == null || player.getPlayerRef() == null || player.getPlayerRef().getPacketHandler() == null) {
            return false;
        }
        player.setMountEntityId(mountNetworkIdValue);
        player.getPlayerRef().getPacketHandler().write(new MountNPC(
                (float) mount.getAnchorX(),
                (float) mount.getAnchorY(),
                (float) mount.getAnchorZ(),
                mountNetworkIdValue
        ));
        return true;
    }

    public static boolean updateCamera(@Nonnull Store<EntityStore> store,
                                       @Nonnull Ref<EntityStore> riderRef,
                                       double speedModifier) {
        Player player = store.getComponent(riderRef, Player.getComponentType());
        if (player == null || player.getPlayerRef() == null || player.getPlayerRef().getPacketHandler() == null) {
            return false;
        }

        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 1.0F;
        settings.rotationLerpSpeed = 1.0F;
        settings.distance = 7.5F;
        settings.speedModifier = sanitizeSpeedModifier(speedModifier);
        settings.allowPitchControls = true;
        settings.displayReticle = true;
        settings.isFirstPerson = false;
        settings.movementForceRotationType = MovementForceRotationType.CameraRotation;
        settings.attachedToType = AttachedToType.LocalPlayer;
        settings.canMoveType = CanMoveType.Always;
        settings.applyMovementType = ApplyMovementType.CharacterController;
        settings.skipCharacterPhysics = true;
        settings.eyeOffset = true;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffsetRaycast;
        player.getPlayerRef().getPacketHandler().writeNoCache(new SetServerCamera(
                ClientCameraView.Custom,
                false,
                settings
        ));
        return true;
    }

    private static float sanitizeSpeedModifier(double speedModifier) {
        if (!Double.isFinite(speedModifier) || speedModifier <= 0.0) {
            return (float) DEFAULT_RIDE_INPUT_SPEED_MODIFIER;
        }
        return (float) Math.min(speedModifier, 100.0);
    }

    public static void placeRiderAtMountAnchor(@Nonnull ComponentAccessor<EntityStore> accessor,
                                               @Nonnull Ref<EntityStore> riderRef,
                                               @Nonnull Ref<EntityStore> mountRef,
                                               @Nonnull TameworkRideMountComponent mount) {
        Player player = accessor.getComponent(riderRef, Player.getComponentType());
        TransformComponent mountTransform = accessor.getComponent(mountRef, TransformComponent.getComponentType());
        if (player == null || mountTransform == null) {
            return;
        }
        Vector3d mountPosition = mountTransform.getPosition();
        var mountRotation = mountTransform.getRotation();
        double yaw = mountRotation == null ? 0.0 : mountRotation.getYaw();
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double worldX = mount.getAnchorX() * cos - mount.getAnchorZ() * sin;
        double worldZ = mount.getAnchorX() * sin + mount.getAnchorZ() * cos;
        player.moveTo(
                riderRef,
                mountPosition.x + worldX,
                mountPosition.y + mount.getAnchorY(),
                mountPosition.z + worldZ,
                accessor
        );
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
                    ClientCameraView.Custom,
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
}
