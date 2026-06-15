package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Emits gated, low-frequency diagnostics for the needs seek movement state machine.
 */
public final class NeedsResourceMovementDiagnostics {
    private static final Logger LOGGER = Logger.getLogger(NeedsResourceMovementDiagnostics.class.getName());
    private static final long REPEAT_LOG_INTERVAL_MS = 2_000L;
    private static final ConcurrentHashMap<String, Long> LAST_LOG_BY_SIGNATURE = new ConcurrentHashMap<>();

    private NeedsResourceMovementDiagnostics() {
    }

    public static void maybeLog(@Nonnull String npcId,
                                @Nullable String roleId,
                                @Nullable String resourceType,
                                @Nullable String stage,
                                @Nullable String detail,
                                @Nullable Vector3d target) {
        maybeLog(npcId, roleId, resourceType, stage, detail, target, null, null);
    }

    public static void maybeLog(@Nonnull String npcId,
                                @Nullable String roleId,
                                @Nullable String resourceType,
                                @Nullable String stage,
                                @Nullable String detail,
                                @Nullable Vector3d target,
                                @Nullable Vector3d currentPosition,
                                @Nullable NeedsResourceMovementProgressTracker.ProgressSnapshot progress) {
        if (!isRuntimeEnabled() || !LOGGER.isLoggable(Level.INFO)) {
            return;
        }
        String normalizedStage = normalize(stage);
        String normalizedDetail = normalize(detail);
        String normalizedResource = normalize(resourceType);
        String formattedTarget = formatTarget(target);
        String formattedCurrent = formatTarget(currentPosition);
        String formattedProgress = formatProgress(progress);
        String signature = npcId
                + '|' + normalizedResource
                + '|' + normalizedStage
                + '|' + normalizedDetail
                + '|' + formattedTarget;
        if (shouldSuppress(signature, System.currentTimeMillis())) {
            return;
        }
        LOGGER.log(Level.INFO, formatMessage(
                npcId,
                roleId,
                normalizedResource,
                normalizedStage,
                normalizedDetail,
                formattedTarget,
                formattedCurrent,
                formattedProgress
        ));
    }

    @Nonnull
    public static String resolveNpcId(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return "<invalid>";
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getUuid() != null) {
            return npc.getUuid().toString();
        }
        return npcRef.toString();
    }

    @Nonnull
    static String formatMessage(@Nonnull String npcId,
                                @Nullable String roleId,
                                @Nonnull String resourceType,
                                @Nonnull String stage,
                                @Nonnull String detail,
                                @Nonnull String target) {
        return formatMessage(npcId, roleId, resourceType, stage, detail, target, "<none>", "n/a");
    }

    @Nonnull
    static String formatMessage(@Nonnull String npcId,
                                @Nullable String roleId,
                                @Nonnull String resourceType,
                                @Nonnull String stage,
                                @Nonnull String detail,
                                @Nonnull String target,
                                @Nonnull String currentPosition,
                                @Nonnull String progress) {
        return String.format(
                Locale.ROOT,
                "Needs seek movement: npc=%s role=%s type=%s stage=%s detail=%s target=%s current=%s progress=%s",
                npcId,
                roleId == null || roleId.isBlank() ? "<unknown>" : roleId,
                resourceType,
                stage,
                detail,
                target,
                currentPosition,
                progress
        );
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim();
    }

    @Nonnull
    private static String formatTarget(@Nullable Vector3d target) {
        if (target == null) {
            return "<none>";
        }
        return String.format(Locale.ROOT, "[%.2f,%.2f,%.2f]", target.x(), target.y(), target.z());
    }

    @Nonnull
    private static String formatProgress(@Nullable NeedsResourceMovementProgressTracker.ProgressSnapshot progress) {
        if (progress == null) {
            return "n/a";
        }
        return String.format(
                Locale.ROOT,
                "startDistance=%.2f,currentDistance=%.2f,closed=%.2f,elapsedMs=%d",
                progress.startDistance(),
                progress.currentDistance(),
                progress.progress(),
                progress.elapsedMs()
        );
    }

    private static boolean shouldSuppress(@Nonnull String signature, long nowMs) {
        Long previous = LAST_LOG_BY_SIGNATURE.put(signature, nowMs);
        return previous != null && nowMs < previous + REPEAT_LOG_INTERVAL_MS;
    }

    private static boolean isRuntimeEnabled() {
        Tamework instance = Tamework.getInstance();
        return instance != null && instance.isDebugNeedsSeekDiagnosticsEnabled();
    }
}
