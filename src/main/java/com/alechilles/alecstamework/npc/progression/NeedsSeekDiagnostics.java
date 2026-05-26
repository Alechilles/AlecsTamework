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
                                @Nullable Vector3d target) {
        Level level = resolveLevel(result);
        if (!isRuntimeEnabled() || !LOGGER.isLoggable(level)) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        String modeKey = npcId + '|' + resourceType;
        String signature = result + '|' + detail + '|' + formatTarget(target) + '|' + cacheHit;
        DiagnosticSnapshot previous = LAST_LOG_BY_NPC_AND_MODE.get(modeKey);
        if (previous != null
                && previous.signature().equals(signature)
                && nowMs < previous.loggedAtMs() + REPEAT_LOG_INTERVAL_MS) {
            return;
        }
        LAST_LOG_BY_NPC_AND_MODE.put(modeKey, new DiagnosticSnapshot(signature, nowMs));
        LOGGER.log(level, String.format(
                Locale.ROOT,
                "Needs seek probe: npc=%s role=%s type=%s result=%s detail=%s cacheHit=%s range=%.2f currentRatio=%s threshold=%s target=%s",
                npcId,
                roleId == null || roleId.isBlank() ? "<unknown>" : roleId,
                resourceType,
                result,
                detail,
                cacheHit,
                searchRange,
                formatRatio(currentRatio),
                formatRatio(ratioBelow),
                formatTarget(target)
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

    private record DiagnosticSnapshot(@Nonnull String signature,
                                      long loggedAtMs) {
    }
}
