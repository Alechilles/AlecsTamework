package com.alechilles.alecstamework.debug;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.avatarflight.MovementIntentProjector;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MouseButtonEvent;
import com.hypixel.hytale.protocol.MouseMotionEvent;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.Vector2i;
import com.hypixel.hytale.protocol.Vector3d;
import com.hypixel.hytale.protocol.WorldInteraction;
import com.hypixel.hytale.protocol.packets.entities.MountMovement;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.protocol.packets.player.MouseInteraction;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * In-memory debug switch and packet/event logger for player input experiments.
 */
public final class PlayerInputDebugProbe {
    private static final Set<UUID> ENABLED_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<UUID, String> LAST_PACKET_SIGNATURES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, MovementIntentProjector.PositionSnapshot> LAST_ABSOLUTE_POSITIONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, MovementIntentProjector.DirectionSnapshot> LAST_BODY_DIRECTIONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, MovementIntentProjector.DirectionSnapshot> LAST_LOOK_DIRECTIONS =
            new ConcurrentHashMap<>();

    private PlayerInputDebugProbe() {
    }

    public static boolean enable(@Nonnull UUID playerUuid) {
        return ENABLED_PLAYERS.add(playerUuid);
    }

    public static boolean disable(@Nonnull UUID playerUuid) {
        LAST_PACKET_SIGNATURES.keySet().removeIf(key -> key.equals(playerUuid));
        LAST_ABSOLUTE_POSITIONS.remove(playerUuid);
        LAST_BODY_DIRECTIONS.remove(playerUuid);
        LAST_LOOK_DIRECTIONS.remove(playerUuid);
        return ENABLED_PLAYERS.remove(playerUuid);
    }

    public static boolean isEnabled(@Nullable UUID playerUuid) {
        return playerUuid != null && ENABLED_PLAYERS.contains(playerUuid);
    }

    public static int enabledCount() {
        return ENABLED_PLAYERS.size();
    }

    public static void logClientMovement(@Nullable PlayerRef playerRef, @Nonnull ClientMovement packet) {
        UUID playerUuid = resolvePlayerUuid(playerRef);
        if (!isEnabled(playerUuid)) {
            return;
        }
        updateDirectionSnapshots(playerUuid, packet.bodyOrientation, packet.lookOrientation);
        MovementIntentProjector.MovementIntent axisProbe = resolveMovementAxisProbe(playerUuid, packet);
        String signature = "client|" + formatPosition(packet.wishMovement)
                + "|" + formatVector(packet.velocity)
                + "|" + formatPosition(packet.absolutePosition)
                + "|" + formatDirection(packet.bodyOrientation)
                + "|" + formatDirection(packet.lookOrientation)
                + "|" + formatStates(packet.movementStates)
                + "|" + formatStates(packet.riderMovementStates)
                + "|" + axisProbe.signature();
        logChanged(playerUuid, signature, String.format(
                "TameworkInput debug: packet=ClientMovement player=%s wish=%s velocity=%s absolute=%s "
                        + "relative=%s mountedTo=%s body=%s look=%s states=%s riderStates=%s axisProbe=%s",
                playerUuid,
                formatPosition(packet.wishMovement),
                formatVector(packet.velocity),
                formatPosition(packet.absolutePosition),
                packet.relativePosition != null,
                packet.mountedTo,
                formatDirection(packet.bodyOrientation),
                formatDirection(packet.lookOrientation),
                formatStates(packet.movementStates),
                formatStates(packet.riderMovementStates),
                axisProbe.summary()
        ));
    }

    public static void logMountMovement(@Nullable PlayerRef playerRef, @Nonnull MountMovement packet) {
        UUID playerUuid = resolvePlayerUuid(playerRef);
        if (!isEnabled(playerUuid)) {
            return;
        }
        String signature = "mount|" + formatPosition(packet.absolutePosition)
                + "|" + formatDirection(packet.bodyOrientation)
                + "|" + formatStates(packet.movementStates);
        logChanged(playerUuid, signature, String.format(
                "TameworkInput debug: packet=MountMovement player=%s absolute=%s body=%s states=%s",
                playerUuid,
                formatPosition(packet.absolutePosition),
                formatDirection(packet.bodyOrientation),
                formatStates(packet.movementStates)
        ));
    }

    public static void logMouseInteraction(@Nullable PlayerRef playerRef, @Nonnull MouseInteraction packet) {
        UUID playerUuid = resolvePlayerUuid(playerRef);
        if (!isEnabled(playerUuid)) {
            return;
        }
        String signature = "mouse|" + packet.activeSlot
                + "|" + packet.itemInHandId
                + "|" + formatMouseButton(packet.mouseButton)
                + "|" + formatMouseMotion(packet.mouseMotion)
                + "|" + formatWorldInteraction(packet.worldInteraction);
        logChanged(playerUuid, signature, String.format(
                "TameworkInput debug: packet=MouseInteraction player=%s slot=%s item=%s button=%s motion=%s world=%s",
                playerUuid,
                packet.activeSlot,
                packet.itemInHandId,
                formatMouseButton(packet.mouseButton),
                formatMouseMotion(packet.mouseMotion),
                formatWorldInteraction(packet.worldInteraction)
        ));
    }

    public static void logPlayerInteract(@Nullable PlayerInteractEvent event) {
        if (event == null || event.getPlayer() == null || !isEnabled(event.getPlayer().getUuid())) {
            return;
        }
        ItemStack item = event.getItemInHand();
        Entity target = event.getTargetEntity();
        log(String.format(
                "TameworkInput debug: event=PlayerInteract player=%s action=%s cancelled=%s item=%s targetEntity=%s targetBlock=%s",
                event.getPlayer().getUuid(),
                event.getActionType(),
                event.isCancelled(),
                item == null || item.isEmpty() ? "<empty>" : item.getItemId(),
                target == null ? "<none>" : target.getUuid(),
                event.getTargetBlock()
        ));
    }

    @Nullable
    private static UUID resolvePlayerUuid(@Nullable PlayerRef playerRef) {
        return playerRef == null ? null : playerRef.getUuid();
    }

    private static void logChanged(@Nonnull UUID playerUuid, @Nonnull String signature, @Nonnull String message) {
        String previous = LAST_PACKET_SIGNATURES.put(playerUuid, signature);
        if (!signature.equals(previous)) {
            log(message);
        }
    }

    private static void log(@Nonnull String message) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(message);
    }

    private static void updateDirectionSnapshots(@Nonnull UUID playerUuid,
                                                 @Nullable Direction bodyOrientation,
                                                 @Nullable Direction lookOrientation) {
        if (bodyOrientation != null) {
            LAST_BODY_DIRECTIONS.put(playerUuid, MovementIntentProjector.fromDirection(bodyOrientation));
        }
        if (lookOrientation != null) {
            LAST_LOOK_DIRECTIONS.put(playerUuid, MovementIntentProjector.fromDirection(lookOrientation));
        }
    }

    @Nonnull
    private static MovementIntentProjector.MovementIntent resolveMovementAxisProbe(@Nonnull UUID playerUuid,
                                                                                   @Nonnull ClientMovement packet) {
        MovementIntentProjector.DirectionSnapshot basis = LAST_LOOK_DIRECTIONS.get(playerUuid);
        String basisName = "look";
        if (basis == null) {
            basis = LAST_BODY_DIRECTIONS.get(playerUuid);
            basisName = "body";
        }
        if (basis == null) {
            basis = new MovementIntentProjector.DirectionSnapshot(0.0, 0.0, 0.0);
            basisName = "default";
        }
        MovementIntentProjector.PositionSnapshot previous = LAST_ABSOLUTE_POSITIONS.get(playerUuid);
        MovementIntentProjector.MovementIntent intent = MovementIntentProjector.projectPacket(
                packet.wishMovement,
                packet.velocity,
                packet.absolutePosition,
                previous,
                basisName,
                basis
        );
        if (packet.absolutePosition != null) {
            LAST_ABSOLUTE_POSITIONS.put(playerUuid, MovementIntentProjector.fromPosition(packet.absolutePosition));
        }
        return intent;
    }

    @Nonnull
    public static String formatStates(@Nullable MovementStates states) {
        if (states == null) {
            return "<none>";
        }
        return "jump=" + states.jumping
                + ",swimJump=" + states.swimJumping
                + ",crouch=" + states.crouching
                + ",forcedCrouch=" + states.forcedCrouching
                + ",sprint=" + states.sprinting
                + ",run=" + states.running
                + ",fly=" + states.flying
                + ",ground=" + states.onGround
                + ",idle=" + states.horizontalIdle
                + ",mounting=" + states.mounting;
    }

    @Nonnull
    public static String formatDirection(@Nullable Direction direction) {
        return direction == null
                ? "<none>"
                : String.format("%.3f/%.3f/%.3f", direction.yaw, direction.pitch, direction.roll);
    }

    @Nonnull
    public static String formatPosition(@Nullable Position position) {
        return position == null
                ? "<none>"
                : String.format("%.3f/%.3f/%.3f", position.x, position.y, position.z);
    }

    @Nonnull
    public static String formatVector(@Nullable Vector3d vector) {
        return vector == null
                ? "<none>"
                : String.format("%.3f/%.3f/%.3f", vector.x, vector.y, vector.z);
    }

    @Nonnull
    public static String formatJomlVector(@Nullable org.joml.Vector3d vector) {
        return vector == null
                ? "<none>"
                : String.format("%.3f/%.3f/%.3f", vector.x, vector.y, vector.z);
    }

    @Nonnull
    public static String formatRef(@Nullable Ref<EntityStore> ref) {
        return ref == null ? "<none>" : ref.toString();
    }

    @Nonnull
    private static String formatMouseButton(@Nullable MouseButtonEvent event) {
        if (event == null) {
            return "<none>";
        }
        return event.mouseButtonType + ":" + event.state + ":clicks=" + event.clicks;
    }

    @Nonnull
    private static String formatMouseMotion(@Nullable MouseMotionEvent event) {
        if (event == null) {
            return "<none>";
        }
        Vector2i motion = event.relativeMotion;
        String motionText = motion == null ? "<none>" : motion.x + "/" + motion.y;
        return "buttons=" + Arrays.toString(event.mouseButtonType) + ",relative=" + motionText;
    }

    @Nonnull
    private static String formatWorldInteraction(@Nullable WorldInteraction interaction) {
        if (interaction == null) {
            return "<none>";
        }
        return "entity=" + interaction.entityId + ",block=" + formatBlock(interaction.blockPosition);
    }

    @Nonnull
    private static String formatBlock(@Nullable BlockPosition position) {
        return position == null ? "<none>" : position.x + "/" + position.y + "/" + position.z;
    }

}
