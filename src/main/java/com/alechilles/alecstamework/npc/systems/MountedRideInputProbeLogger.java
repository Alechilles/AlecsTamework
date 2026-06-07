package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.Vector3d;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Focused mounted-ride input probe for validating which rider axes survive the camera/mount setup.
 */
public final class MountedRideInputProbeLogger {
    private static final long LOG_INTERVAL_MS = 250L;
    private static final ConcurrentMap<String, Long> LAST_LOG_MS_BY_SOURCE = new ConcurrentHashMap<>();

    private MountedRideInputProbeLogger() {
    }

    public static void logClientMovement(@Nonnull TameworkRideMountComponent mount,
                                         @Nullable Position rawWish,
                                         @Nullable Vector3d rawVelocity,
                                         @Nullable MovementStates movementStates,
                                         @Nullable MovementStates riderMovementStates,
                                         boolean capturedMovementIntent) {
        if (!shouldLog("clientMovement")) {
            return;
        }
        log(
                "TameworkRide inputProbe source=clientMovement rawWish=%s rawVelocity=%s capturedMovement=%s " +
                        "movementStates=%s riderStates=%s snapshot=%s",
                formatPosition(rawWish),
                formatVector(rawVelocity),
                capturedMovementIntent,
                formatStates(movementStates),
                formatStates(riderMovementStates),
                formatSnapshot(mount)
        );
    }

    public static void logPlayerInputQueue(@Nonnull TameworkRideMountComponent mount,
                                           int queueSize,
                                           @Nonnull String queueSummary,
                                           boolean sawRawWish,
                                           double rawWishX,
                                           double rawWishY,
                                           double rawWishZ,
                                           @Nonnull String inferredSources) {
        if (!shouldLog("playerInputQueue")) {
            return;
        }
        log(
                "TameworkRide inputProbe source=playerInputQueue queueSize=%s queue=%s rawWish=%s inferredSources=%s " +
                        "snapshot=%s",
                queueSize,
                queueSummary,
                sawRawWish ? formatTriple(rawWishX, rawWishY, rawWishZ) : "<none>",
                inferredSources.isBlank() ? "<none>" : inferredSources,
                formatSnapshot(mount)
        );
    }

    private static boolean shouldLog(@Nonnull String source) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_LOG_MS_BY_SOURCE.get(source);
        if (last != null && now - last < LOG_INTERVAL_MS) {
            return false;
        }
        LAST_LOG_MS_BY_SOURCE.put(source, now);
        return true;
    }

    private static void log(@Nonnull String message, Object... args) {
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.INFO).log(message, args);
        }
    }

    private static String formatPosition(@Nullable Position position) {
        return position == null ? "<none>" : formatTriple(position.x, position.y, position.z);
    }

    private static String formatVector(@Nullable Vector3d vector) {
        return vector == null ? "<none>" : formatTriple(vector.x, vector.y, vector.z);
    }

    private static String formatTriple(double x, double y, double z) {
        return x + "/" + y + "/" + z;
    }

    private static String formatStates(@Nullable MovementStates states) {
        if (states == null) {
            return "<none>";
        }
        return "jump=" + states.jumping
                + ",crouch=" + states.crouching
                + ",sprint=" + states.sprinting
                + ",fly=" + states.flying
                + ",mounting=" + states.mounting
                + ",ground=" + states.onGround;
    }

    private static String formatSnapshot(@Nonnull TameworkRideMountComponent mount) {
        return "wish=" + mount.getWishX() + "/" + mount.getWishY() + "/" + mount.getWishZ()
                + ",hasWish=" + mount.hasWishMovement()
                + ",backBrake=" + mount.isRiderBackwardBrakeInput()
                + ",head=" + mount.getHeadYaw() + "/" + mount.getHeadPitch() + "/" + mount.hasHeadRotation()
                + ",body=" + mount.getBodyYaw() + "/" + mount.getBodyPitch() + "/" + mount.hasBodyRotation()
                + ",states=jump:" + mount.isRiderJumping()
                + ",crouch:" + mount.isRiderCrouching()
                + ",sprint:" + mount.isRiderSprinting()
                + ",fly:" + mount.isRiderFlying();
    }
}
