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
 * follows the companion if it moves, and while the camera is attached to another entity the player cannot walk.
 *
 * <p>Call on the player's world thread from a page event or deferred world task, never inside an ECS system
 * iteration. Every path that closes the panel must call {@link #release(PlayerRef)}.
 */
final class CompanionInspectCamera {
    /** How far the camera looks down at the companion, in radians (about 14 degrees). */
    private static final float PITCH = -0.25f;
    /** Engine camera glide speed; the camera demo uses 0.2. */
    private static final float LERP = 0.2f;
    /** A slightly narrower view than the default, for a zoomed-in look. */
    private static final float FOV = 60f;
    /** How far the companion sits from the screen centre, as a share of the camera distance. */
    private static final double SIDE_SHIFT = 0.35;
    /** Card centres closer than this to the screen centre (canvas units) leave the companion centred. */
    private static final int CENTRED_CARD = 200;

    private CompanionInspectCamera() {
    }

    /**
     * Locks the camera onto the companion. Returns false, sending nothing, when the player is riding or flying (their
     * camera is already in use) or either entity is missing what the camera needs.
     */
    static boolean apply(@Nonnull Player player,
                         @Nonnull Ref<EntityStore> playerRef,
                         @Nonnull Ref<EntityStore> npcRef,
                         @Nonnull Store<EntityStore> store,
                         @Nonnull CompanionHudPlacement cardPlacement) {
        PlayerRef uiPlayer = player.getPlayerRef();
        if (uiPlayer == null || uiPlayer.getPacketHandler() == null || cameraInUse(player, playerRef, store)) {
            return false;
        }
        NetworkId npcNetworkId = store.getComponent(npcRef, NetworkId.getComponentType());
        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (npcNetworkId == null || npcTransform == null || playerTransform == null) {
            return false;
        }
        Vector3d npc = npcTransform.getPosition();
        Vector3d viewer = playerTransform.getPosition();
        double dx = npc.x - viewer.x;
        double dz = npc.z - viewer.z;
        // Engine yaw: forward is (-sin yaw, 0, -cos yaw). Look from the player towards the companion.
        float yaw = dx * dx + dz * dz < 1.0e-4 ? 0f : (float) Math.atan2(-dx, -dz);

        float eyeHeight = ModelComponent.getEyeHeight(npcRef, store);
        float distance = (float) Math.max(2.2, Math.min(8.0, 1.6 + eyeHeight * 2.0));
        double shift = sideOfCard(cardPlacement) * SIDE_SHIFT * distance;

        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = LERP;
        settings.rotationLerpSpeed = LERP;
        settings.isFirstPerson = false;
        settings.displayReticle = false;
        settings.hideHeldItem = true;
        settings.attachedToType = AttachedToType.EntityId;
        settings.attachedToEntityId = npcNetworkId.getId();
        settings.followAttachedEntity = true;
        settings.eyeOffset = true;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffsetRaycast;
        settings.distance = distance;
        // Moving the pivot to the camera's right moves the companion left on screen, and the reverse.
        settings.positionOffset = new Position(Math.cos(yaw) * shift, 0.0, -Math.sin(yaw) * shift);
        settings.rotationType = RotationType.Custom;
        settings.rotation = new Direction(yaw, PITCH, 0f);
        settings.baseFov = FOV;
        uiPlayer.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
        return true;
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
