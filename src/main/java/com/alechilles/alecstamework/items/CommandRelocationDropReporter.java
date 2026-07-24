package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import javax.annotation.Nullable;

/** Emits diagnostics when a relocation is abandoned after bounded retries. */
final class CommandRelocationDropReporter {
    @Nullable
    private final HytaleLogger logger;
    private final BiConsumer<Level, String> diagnostic;

    CommandRelocationDropReporter(@Nullable HytaleLogger logger,
                                  BiConsumer<Level, String> diagnostic) {
        this.logger = logger;
        this.diagnostic = diagnostic;
    }

    void report(PendingRelocation pending, long droppedAtMs) {
        diagnostic.accept(
                Level.WARNING,
                "Dropped relocation after retries for npc=" + pending.npcUuid
                        + ", retries=" + pending.retryAttempts
                        + ", ageMs=" + (droppedAtMs - pending.queuedAtMs)
                        + "; no lifecycle transition was inferred"
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
