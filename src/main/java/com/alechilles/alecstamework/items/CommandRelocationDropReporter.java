package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import javax.annotation.Nullable;

/** Notifies linked-state persistence and emits terminal relocation-drop diagnostics. */
final class CommandRelocationDropReporter {
    @Nullable
    private final HytaleLogger logger;
    private final BiConsumer<Level, String> diagnostic;
    @Nullable
    private volatile CommandRelocationDropListener listener;

    CommandRelocationDropReporter(@Nullable HytaleLogger logger,
                                  BiConsumer<Level, String> diagnostic) {
        this.logger = logger;
        this.diagnostic = diagnostic;
    }

    void setListener(@Nullable CommandRelocationDropListener listener) {
        this.listener = listener;
    }

    void report(PendingRelocation pending, long droppedAtMs) {
        CommandRelocationDropListener current = listener;
        if (current != null) {
            try {
                current.onRelocationDropped(
                        pending.npcUuid,
                        pending.ownerUuid,
                        pending.sourceHintPosition,
                        pending.alternateSourceHintPosition,
                        pending.destination,
                        pending.queuedAtMs,
                        droppedAtMs,
                        pending.retryAttempts
                );
            } catch (RuntimeException | LinkageError exception) {
                diagnostic.accept(Level.WARNING,
                        "Relocation drop callback failed for npc=" + pending.npcUuid);
            }
        }
        diagnostic.accept(
                Level.WARNING,
                "Dropped relocation as lost for npc=" + pending.npcUuid
                        + ", retries=" + pending.retryAttempts
                        + ", ageMs=" + (droppedAtMs - pending.queuedAtMs)
        );
        Tamework plugin = Tamework.getInstance();
        if (logger != null && plugin != null && plugin.isDebugLagEnabled()) {
            logger.at(Level.WARNING).log(
                    "Tamework lag probe: dropping relocation after retries (npc="
                            + pending.npcUuid + ", retries=" + pending.retryAttempts
                            + ", ageMs=" + (droppedAtMs - pending.queuedAtMs) + ")."
            );
        }
    }
}
