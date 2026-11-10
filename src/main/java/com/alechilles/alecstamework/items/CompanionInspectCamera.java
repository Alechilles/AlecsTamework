package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.avatarflight.AvatarFlightComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.ui.CompanionHudPlacement;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.Position;
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
 * stood on, a little above it and looking down, and glides there and back. The companion sits off centre, away from
 * the panel, so the panel does not cover it. The engine's server camera ({@code SetServerCamera}) does the work: it
 * eases after the companion if it moves, and while the camera is attached to another entity the player cannot walk
 * and stays where they stood.
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
     * Glide speeds while a turn is animated in steps. The client derives the camera position from its eased angle and
     * then eases the position again, so a slow position glide trails the aim; tracking position almost immediately
     * keeps the orbit and the aim in step. The rotation glide smooths the gaps between steps.
     */
    private static final float TURN_POSITION_LERP = 0.6f;
    private static final float TURN_ROTATION_LERP = 0.5f;
    /** Steps per animated turn and the delay between them; together about a third of a second. */
    static final int TURN_STEPS = 8;
    static final long TURN_STEP_MS = 40L;
    /** A slightly narrower view than the default, for a zoomed-in look. */
    private static final float FOV = 60f;
    /** How far the companion sits from the screen centre, as a share of the camera distance. */
    private static final double SIDE_SHIFT = 0.35;
    /** Card centres closer than this to the screen centre (canvas units) leave the companion centred. */
    private static final int CENTRED_CARD = 200;
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
                       @Nonnull CompanionHudPlacement cardPlacement,
                       @Nullable Float yaw,
                       boolean turning) {
        PlayerRef uiPlayer = player.getPlayerRef();
        if (uiPlayer == null || uiPlayer.getPacketHandler() == null || cameraInUse(player, playerRef, store)) {
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
        double shift = sideOfCard(cardPlacement) * SIDE_SHIFT * distance;

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
        // Moving the pivot to the camera's right moves the companion left on screen, and the reverse.
        settings.positionOffset = new Position(Math.cos(look) * shift, 0.0, -Math.sin(look) * shift);
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

    /**
     * The yaw at {@code step} of {@link #TURN_STEPS} on the way from {@code from} to {@code to}, eased in and out so the
     * turn starts and stops gently. The sideways framing shift follows the same yaw, so it never jumps.
     */
    static float turnStep(float from, float to, int step) {
        float t = Math.min(1f, Math.max(0f, step / (float) TURN_STEPS));
        float eased = t * t * (3f - 2f * t);
        return from + (to - from) * eased;
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

    /** +1 when the card sits right of centre (the companion goes left), -1 when left, 0 when roughly centred. */
    private static int sideOfCard(@Nonnull CompanionHudPlacement card) {
        int half = CompanionHudPlacement.DESIGN_WIDTH / 2;
        int cardHalf = CompanionHudPlacement.CARD_WIDTH / 2;
        int centre = switch (card.point().column()) {
            case 0 -> -half + card.x() + cardHalf;
            case 2 -> half + card.x() - cardHalf;
            default -> card.x();
        };
        return centre > CENTRED_CARD ? 1 : centre < -CENTRED_CARD ? -1 : 0;
    }

    private static boolean cameraInUse(@Nonnull Player player, @Nonnull Ref<EntityStore> playerRef,
                                       @Nonnull Store<EntityStore> store) {
        return player.getMountEntityId() != 0
                || has(playerRef, store, TameworkRideRiderComponent.getComponentType())
                || has(playerRef, store, TameworkMountedGlideRiderComponent.getComponentType())
                || has(playerRef, store, AvatarFlightComponent.getComponentType());
    }

    private static boolean has(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                               @Nullable ComponentType<EntityStore, ?> type) {
        return type != null && store.getComponent(ref, type) != null;
    }
}
