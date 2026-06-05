package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

    private static boolean isRuntimeEnabled() {
        Tamework instance = Tamework.getInstance();
        return instance != null && instance.isDebugNeedsConsumeDiagnosticsEnabled();
    }
}
