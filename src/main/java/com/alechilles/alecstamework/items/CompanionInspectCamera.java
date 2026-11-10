package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.avatarflight.AvatarFlightComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Prototype: while Companion Inspect is open, the player's camera locks onto the companion from the side the player
 * stood on, a little above it and looking down, and glides there and back. The companion stays in the screen centre;
 * the panel moves to the screen edge instead (see {@code CompanionHudPlacement.CARD_CAMERA_DEFAULT}). The engine's
 * server camera ({@code SetServerCamera}) does the work: it eases after the companion if it moves, and while the camera
 * is attached to another entity the player cannot walk and stays where they stood.
 *
 * <p>Turning sends one camera per click and leaves the motion to the client, which eases the angle every frame and
 * places the camera on its orbit from that angle. Nothing else moves during a turn: an off-centre framing would need
 * a world-space offset the server recomputes per angle, which steps instead of gliding.
 *
 * <p>Call on the player's world thread from a page event or deferred world task, never inside an ECS system
 * iteration. Every path that closes the panel must call {@link #release(PlayerRef)}.
 */
final class CompanionInspectCamera {
    /** How far the camera looks down at the companion, in radians (about 14 degrees). */
    private static final float PITCH = -0.25f;
    /** Engine camera glide speed for opening; the camera demo uses 0.2. */
    private static final float LERP = 0.2f;
    /**
     * Glide speeds for a turn. The client places the camera on its orbit from its eased angle and then eases the
     * position again; following that position at once (1) keeps the camera on the orbit and in step with its aim,
     * and the slower rotation glide makes the turn itself smooth.
     */
    private static final float TURN_POSITION_LERP = 1.0f;
    private static final float TURN_ROTATION_LERP = 0.1f;
    /** A slightly narrower view than the default, for a zoomed-in look. */
    private static final float FOV = 60f;
    /** How far one rotate click turns the camera around the companion, in radians (30 degrees). */
    private static final float TURN = (float) (Math.PI / 6.0);

    private CompanionInspectCamera() {
    }

    /**
     * Locks the camera onto the companion, looking along {@code yaw}, or from the player towards the companion when
     * {@code yaw} is null. Returns the yaw used, or null, sending nothing, when the player is riding or flying (their
     * camera is already in use) or either entity is missing what the camera needs. Sending again with another yaw
     * glides the camera there.
     */
    @Nullable
    static Float apply(@Nonnull Player player,
                       @Nonnull Ref<EntityStore> playerRef,
                       @Nonnull Ref<EntityStore> npcRef,
                       @Nonnull Store<EntityStore> store,
                       @Nullable Float yaw,
                       boolean turning) {
        PlayerRef uiPlayer = player.getPlayerRef();
        if (uiPlayer == null || uiPlayer.getPacketHandler() == null || !available(player, playerRef, store)) {
            return null;
        }
        NetworkId npcNetworkId = store.getComponent(npcRef, NetworkId.getComponentType());
        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (npcNetworkId == null || npcTransform == null || playerTransform == null) {
            return null;
        }
        float look = yaw != null ? yaw : towards(playerTransform.getPosition(), npcTransform.getPosition());

        float eyeHeight = ModelComponent.getEyeHeight(npcRef, store);
        float distance = (float) Math.max(2.2, Math.min(8.0, 1.6 + eyeHeight * 2.0));

        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = turning ? TURN_POSITION_LERP : LERP;
        settings.rotationLerpSpeed = turning ? TURN_ROTATION_LERP : LERP;
        settings.isFirstPerson = false;
        settings.displayReticle = false;
        settings.hideHeldItem = true;
        settings.attachedToType = AttachedToType.EntityId;
        settings.attachedToEntityId = npcNetworkId.getId();
        // Never true here: the client then moves the local player onto the followed entity every tick (spectator
        // behaviour). Without it the camera still tracks the companion, eased by the lerp speeds above.
        settings.followAttachedEntity = false;
        settings.eyeOffset = true;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffsetRaycast;
        settings.distance = distance;
        settings.rotationType = RotationType.Custom;
        settings.rotation = new Direction(look, PITCH, 0f);
        settings.baseFov = FOV;
        uiPlayer.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
        return look;
    }

    /** The yaw after one rotate click: -1 moves the camera left around the companion, +1 right. */
    static float turned(float yaw, int direction) {
        return yaw - Integer.signum(direction) * TURN;
    }

    /** Engine yaw: forward is (-sin yaw, 0, -cos yaw). Looks from {@code from} towards {@code to}. */
    private static float towards(@Nonnull Vector3d from, @Nonnull Vector3d to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return dx * dx + dz * dz < 1.0e-4 ? 0f : (float) Math.atan2(-dx, -dz);
    }

    /** Hands the normal camera back, as the engine's {@code /camera reset} does. */
    static void release(@Nullable PlayerRef player) {
        if (player != null && player.getPacketHandler() != null) {
            player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
        }
    }

    /** Whether the camera can be taken: not while the player rides or flies, whose camera is already in use. */
    static boolean available(@Nonnull Player player, @Nonnull Ref<EntityStore> playerRef,
                             @Nonnull Store<EntityStore> store) {
        return !(player.getMountEntityId() != 0
                || has(playerRef, store, TameworkRideRiderComponent.getComponentType())
                || has(playerRef, store, TameworkMountedGlideRiderComponent.getComponentType())
                || has(playerRef, store, AvatarFlightComponent.getComponentType()));
    }

    private static boolean has(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                               @Nullable ComponentType<EntityStore, ?> type) {
        return type != null && store.getComponent(ref, type) != null;
    }
}
