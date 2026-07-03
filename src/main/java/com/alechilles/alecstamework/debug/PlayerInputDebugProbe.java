package com.alechilles.alecstamework.debug;

import com.alechilles.alecstamework.Tamework;
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
    private static final ConcurrentHashMap<UUID, PositionSnapshot> LAST_ABSOLUTE_POSITIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, DirectionSnapshot> LAST_BODY_DIRECTIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, DirectionSnapshot> LAST_LOOK_DIRECTIONS = new ConcurrentHashMap<>();

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
        MovementAxisProbe axisProbe = resolveMovementAxisProbe(playerUuid, packet);
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
            LAST_BODY_DIRECTIONS.put(playerUuid, DirectionSnapshot.from(bodyOrientation));
        }
        if (lookOrientation != null) {
            LAST_LOOK_DIRECTIONS.put(playerUuid, DirectionSnapshot.from(lookOrientation));
        }
    }

    @Nonnull
    private static MovementAxisProbe resolveMovementAxisProbe(@Nonnull UUID playerUuid,
                                                              @Nonnull ClientMovement packet) {
        DirectionSnapshot basis = LAST_LOOK_DIRECTIONS.get(playerUuid);
        String basisName = "look";
        if (basis == null) {
            basis = LAST_BODY_DIRECTIONS.get(playerUuid);
            basisName = "body";
        }
        if (basis == null) {
            basis = new DirectionSnapshot(0.0, 0.0, 0.0);
            basisName = "default";
        }

        AxisProjection wish = packet.wishMovement == null
                ? AxisProjection.none()
                : project(packet.wishMovement.x, packet.wishMovement.z, basis);
        AxisProjection velocity = packet.velocity == null
                ? AxisProjection.none()
                : project(packet.velocity.x, packet.velocity.z, basis);
        AxisProjection absolute = resolveAbsoluteProjection(playerUuid, packet.absolutePosition, basis);
        return new MovementAxisProbe(basisName, basis, wish, velocity, absolute);
    }

    @Nonnull
    private static AxisProjection resolveAbsoluteProjection(@Nonnull UUID playerUuid,
                                                            @Nullable Position absolutePosition,
                                                            @Nonnull DirectionSnapshot basis) {
        if (absolutePosition == null) {
            return AxisProjection.none();
        }
        PositionSnapshot current = PositionSnapshot.from(absolutePosition);
        PositionSnapshot previous = LAST_ABSOLUTE_POSITIONS.put(playerUuid, current);
        if (previous == null) {
            return AxisProjection.first();
        }
        return project(current.x() - previous.x(), current.z() - previous.z(), basis);
    }

    @Nonnull
    private static AxisProjection project(double worldX, double worldZ, @Nonnull DirectionSnapshot basis) {
        double horizontalLength = Math.sqrt(worldX * worldX + worldZ * worldZ);
        if (horizontalLength <= 0.0001) {
            return AxisProjection.idle();
        }
        double normalizedX = worldX / horizontalLength;
        double normalizedZ = worldZ / horizontalLength;
        double forwardX = -Math.sin(basis.yaw());
        double forwardZ = -Math.cos(basis.yaw());
        double rightX = -Math.sin(basis.yaw() - Math.PI / 2.0);
        double rightZ = -Math.cos(basis.yaw() - Math.PI / 2.0);
        double strafe = clamp(normalizedX * rightX + normalizedZ * rightZ, -1.0, 1.0);
        double forward = clamp(normalizedX * forwardX + normalizedZ * forwardZ, -1.0, 1.0);
        return new AxisProjection(forward, strafe, horizontalLength, label(forward, strafe));
    }

    @Nonnull
    private static String label(double forward, double strafe) {
        double absForward = Math.abs(forward);
        double absStrafe = Math.abs(strafe);
        if (absForward < 0.25 && absStrafe < 0.25) {
            return "idle";
        }
        if (absForward >= absStrafe * 1.25) {
            return forward > 0.0 ? "forward" : "back";
        }
        if (absStrafe >= absForward * 1.25) {
            return strafe > 0.0 ? "right" : "left";
        }
        String vertical = forward > 0.0 ? "forward" : "back";
        String horizontal = strafe > 0.0 ? "right" : "left";
        return vertical + "+" + horizontal;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private record PositionSnapshot(double x, double y, double z) {
        private static PositionSnapshot from(@Nonnull Position position) {
            return new PositionSnapshot(position.x, position.y, position.z);
        }
    }

    private record DirectionSnapshot(double yaw, double pitch, double roll) {
        private static DirectionSnapshot from(@Nonnull Direction direction) {
            return new DirectionSnapshot(direction.yaw, direction.pitch, direction.roll);
        }
    }

    private record AxisProjection(double forward, double strafe, double magnitude, @Nonnull String label) {
        private static AxisProjection none() {
            return new AxisProjection(0.0, 0.0, 0.0, "<none>");
        }

        private static AxisProjection first() {
            return new AxisProjection(0.0, 0.0, 0.0, "<first>");
        }

        private static AxisProjection idle() {
            return new AxisProjection(0.0, 0.0, 0.0, "idle");
        }

        @Nonnull
        private String summary(@Nonnull String source) {
            return source + ":" + label + "(f=" + formatAxis(forward) +
                    ",s=" + formatAxis(strafe) +
                    ",mag=" + formatMagnitude(magnitude) + ")";
        }

        @Nonnull
        private String signature() {
            return label + ":" + formatAxis(forward) + ":" + formatAxis(strafe);
        }

        @Nonnull
        private static String formatAxis(double value) {
            return String.format("%.2f", value);
        }

        @Nonnull
        private static String formatMagnitude(double value) {
            return String.format("%.3f", value);
        }
    }

    private record MovementAxisProbe(@Nonnull String basisName,
                                     @Nonnull DirectionSnapshot basis,
                                     @Nonnull AxisProjection wish,
                                     @Nonnull AxisProjection velocity,
                                     @Nonnull AxisProjection absolute) {
        @Nonnull
        private String summary() {
            return "basis=" + basisName + "(yaw=" + String.format("%.3f", basis.yaw()) + ") " +
                    wish.summary("wish") + " " +
                    velocity.summary("velocity") + " " +
                    absolute.summary("absoluteDelta");
        }

        @Nonnull
        private String signature() {
            return basisName + ":" + String.format("%.2f", basis.yaw()) + "|" +
                    wish.signature() + "|" + velocity.signature() + "|" + absolute.signature();
        }
    }
}
