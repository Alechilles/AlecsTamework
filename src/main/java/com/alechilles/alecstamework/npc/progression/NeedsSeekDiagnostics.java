package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import org.joml.Vector3d;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Emits throttled diagnostics for needs-seek target resolution.
 */
public final class NeedsSeekDiagnostics {
    private static final Logger LOGGER = Logger.getLogger(NeedsSeekDiagnostics.class.getName());
    private static final long REPEAT_LOG_INTERVAL_MS = 2_000L;
    private static final ConcurrentHashMap<String, DiagnosticSnapshot> LAST_LOG_BY_NPC_AND_MODE = new ConcurrentHashMap<>();

    private NeedsSeekDiagnostics() {
    }

    public static void maybeLog(@Nonnull String npcId,
                                @Nullable String roleId,
                                @Nonnull String resourceType,
                                @Nonnull String result,
                                @Nonnull String detail,
                                double searchRange,
                                @Nullable Double currentRatio,
                                @Nullable Double ratioBelow,
                                boolean cacheHit,
                                @Nullable Vector3d target,
                                @Nullable Vector3d scanPosition) {
        Level level = resolveLevel(result);
        NeedsTelemetryDiagnostics.recordSeekFailure(
                roleId,
                resourceType,
                result,
                detail,
                searchRange,
                currentRatio,
                cacheHit
        );
        if (!isRuntimeEnabled() || !LOGGER.isLoggable(level)) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        String modeKey = npcId + '|' + resourceType;
        String signature = result + '|' + detail + '|' + formatTarget(target) + '|' + cacheHit + '|' + formatBlock(scanPosition);
        DiagnosticSnapshot previous = LAST_LOG_BY_NPC_AND_MODE.get(modeKey);
        if (previous != null
                && previous.signature().equals(signature)
                && nowMs < previous.loggedAtMs() + REPEAT_LOG_INTERVAL_MS) {
            return;
        }
        LAST_LOG_BY_NPC_AND_MODE.put(modeKey, new DiagnosticSnapshot(signature, nowMs));
        LOGGER.log(level, String.format(
                Locale.ROOT,
                "Needs seek probe: npc=%s role=%s type=%s result=%s detail=%s cacheHit=%s range=%.2f currentRatio=%s threshold=%s scanBlock=%s target=%s",
                npcId,
                roleId == null || roleId.isBlank() ? "<unknown>" : roleId,
                resourceType,
                result,
                detail,
                cacheHit,
                searchRange,
                formatRatio(currentRatio),
                formatRatio(ratioBelow),
                formatBlock(scanPosition),
                formatTarget(target)
        ));
    }

    public static void maybeLog(@Nonnull String npcId,
                                @Nullable String roleId,
                                @Nonnull String resourceType,
                                @Nonnull String result,
                                @Nonnull String detail,
                                double searchRange,
                                @Nullable Double currentRatio,
                                @Nullable Double ratioBelow,
                                boolean cacheHit,
                                @Nullable Vector3d target) {
        maybeLog(
                npcId,
                roleId,
                resourceType,
                result,
                detail,
                searchRange,
                currentRatio,
                ratioBelow,
                cacheHit,
                target,
                null
        );
    }

    public static void maybeLogPreflight(@Nonnull String npcId,
                                         @Nullable String roleId,
                                         @Nonnull String resourceType,
                                         @Nonnull String status,
                                         @Nonnull String reason,
                                         @Nonnull String sourceReason,
                                         double stopDistance,
                                         @Nullable Vector3d target,
                                         @Nullable Vector3d currentPosition) {
        if (!isRuntimeEnabled() || !LOGGER.isLoggable(Level.INFO)) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        String modeKey = npcId + '|' + resourceType + "|preflight";
        String signature = status
                + '|'
                + reason
                + '|'
                + sourceReason
                + '|'
                + formatTarget(target)
                + '|'
                + String.format(Locale.ROOT, "%.2f", stopDistance);
        DiagnosticSnapshot previous = LAST_LOG_BY_NPC_AND_MODE.get(modeKey);
        if (previous != null
                && previous.signature().equals(signature)
                && nowMs < previous.loggedAtMs() + REPEAT_LOG_INTERVAL_MS) {
            return;
        }
        LAST_LOG_BY_NPC_AND_MODE.put(modeKey, new DiagnosticSnapshot(signature, nowMs));
        LOGGER.log(Level.INFO, String.format(
                Locale.ROOT,
                "Needs seek preflight: npc=%s role=%s type=%s status=%s reason=%s sourceReason=%s stopDistance=%.2f current=%s target=%s distance=%s",
                npcId,
                roleId == null || roleId.isBlank() ? "<unknown>" : roleId,
                resourceType,
                status,
                reason,
                sourceReason,
                stopDistance,
                formatTarget(currentPosition),
                formatTarget(target),
                formatDistance(currentPosition, target)
        ));
    }

    @Nonnull
    private static Level resolveLevel(@Nonnull String result) {
        return "target_found".equals(result) ? Level.INFO : Level.WARNING;
    }

    private static boolean isRuntimeEnabled() {
        Tamework instance = Tamework.getInstance();
        return instance != null && instance.isDebugNeedsSeekDiagnosticsEnabled();
    }

    /** Returns the cheap runtime toggle without resolving an NPC context. */
    public static boolean isEnabled() {
        return isRuntimeEnabled();
    }

    @Nonnull
    private static String formatRatio(@Nullable Double value) {
        if (value == null || !Double.isFinite(value)) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    @Nonnull
    private static String formatTarget(@Nullable Vector3d target) {
        if (target == null
                || !Double.isFinite(target.x)
                || !Double.isFinite(target.y)
                || !Double.isFinite(target.z)) {
            return "<none>";
        }
        return String.format(Locale.ROOT, "[%.2f,%.2f,%.2f]", target.x, target.y, target.z);
    }

    @Nonnull
    private static String formatBlock(@Nullable Vector3d position) {
        if (position == null
                || !Double.isFinite(position.x)
                || !Double.isFinite(position.y)
                || !Double.isFinite(position.z)) {
            return "<unknown>";
        }
        return String.format(
                Locale.ROOT,
                "[%d,%d,%d]",
                (int) Math.floor(position.x),
                (int) Math.floor(position.y),
                (int) Math.floor(position.z)
        );
    }

    @Nonnull
    private static String formatDistance(@Nullable Vector3d first, @Nullable Vector3d second) {
        if (first == null
                || second == null
                || !Double.isFinite(first.x)
                || !Double.isFinite(first.y)
                || !Double.isFinite(first.z)
                || !Double.isFinite(second.x)
                || !Double.isFinite(second.y)
                || !Double.isFinite(second.z)) {
            return "n/a";
        }
        double dx = first.x - second.x;
        double dy = first.y - second.y;
        double dz = first.z - second.z;
        return String.format(Locale.ROOT, "%.2f", Math.sqrt((dx * dx) + (dy * dy) + (dz * dz)));
    }

    private record DiagnosticSnapshot(@Nonnull String signature,
                                      long loggedAtMs) {
    }
}
