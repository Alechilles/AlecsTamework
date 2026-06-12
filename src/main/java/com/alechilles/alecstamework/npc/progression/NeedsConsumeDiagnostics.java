package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Builds and emits structured diagnostics for action-driven needs consume attempts.
 */
final class NeedsConsumeDiagnostics {
    private static final Logger LOGGER = Logger.getLogger(NeedsConsumeDiagnostics.class.getName());

    private NeedsConsumeDiagnostics() {
    }

    enum LogLevel {
        INFO(Level.INFO),
        FINE(Level.FINE);

        private final Level javaLevel;

        LogLevel(Level javaLevel) {
            this.javaLevel = javaLevel;
        }
    }

    static void appendFailureReason(@Nonnull StringBuilder target, @Nonnull String reason) {
        if (target.length() > 0) {
            target.append(',');
        }
        target.append(reason);
    }

    @Nonnull
    static String resolveNpcId(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return "<invalid>";
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getUuid() != null) {
            return npc.getUuid().toString();
        }
        return npcRef.toString();
    }

    static void maybeLogConsume(boolean diagnostics,
                                @Nonnull LogLevel level,
                                @Nonnull String npcId,
                                @Nullable String roleId,
                                @Nonnull String mode,
                                @Nonnull String reason,
                                int consumedItems,
                                double hungerGain,
                                double thirstGain) {
        if (level == LogLevel.INFO) {
            NeedsTelemetryDiagnostics.recordConsumeFailure(roleId, mode, reason, consumedItems, hungerGain, thirstGain);
        }
        if (!diagnostics || !isRuntimeEnabled() || !LOGGER.isLoggable(level.javaLevel)) {
            return;
        }
        LOGGER.log(level.javaLevel, String.format(
                "Needs consume attempt: npc=%s role=%s mode=%s consumedItems=%d hungerGain=%.2f thirstGain=%.2f result=%s",
                npcId,
                roleId == null || roleId.isBlank() ? "<unknown>" : roleId,
                mode,
                consumedItems,
                hungerGain,
                thirstGain,
                reason
        ));
    }

    static void maybeLogRuntime(boolean diagnostics,
                                @Nonnull String npcId,
                                @Nullable String roleId,
                                @Nonnull String mode,
                                @Nonnull String stage,
                                @Nonnull String result,
                                int consumedItems,
                                double hungerGain,
                                double thirstGain,
                                @Nullable Vector3d target) {
        if (!diagnostics || !isRuntimeEnabled() || !LOGGER.isLoggable(Level.INFO)) {
            return;
        }
        LOGGER.log(Level.INFO, formatRuntimeMessage(
                npcId,
                roleId,
                mode,
                stage,
                result,
                consumedItems,
                hungerGain,
                thirstGain,
                target
        ));
    }

    @Nonnull
    static String formatRuntimeMessage(@Nonnull String npcId,
                                       @Nullable String roleId,
                                       @Nonnull String mode,
                                       @Nonnull String stage,
                                       @Nonnull String result,
                                       int consumedItems,
                                       double hungerGain,
                                       double thirstGain,
                                       @Nullable Vector3d target) {
        return String.format(
                Locale.ROOT,
                "Needs consume runtime: npc=%s role=%s mode=%s stage=%s result=%s consumedItems=%d hungerGain=%.2f thirstGain=%.2f target=%s",
                npcId,
                roleId == null || roleId.isBlank() ? "<unknown>" : roleId,
                mode,
                stage,
                result,
                consumedItems,
                hungerGain,
                thirstGain,
                formatTarget(target)
        );
    }

    @Nonnull
    private static String formatTarget(@Nullable Vector3d target) {
        if (target == null) {
            return "<none>";
        }
        return String.format(Locale.ROOT, "[%.2f,%.2f,%.2f]", target.x(), target.y(), target.z());
    }

    private static boolean isRuntimeEnabled() {
        Tamework instance = Tamework.getInstance();
        return instance != null && instance.isDebugNeedsConsumeDiagnosticsEnabled();
    }
}
