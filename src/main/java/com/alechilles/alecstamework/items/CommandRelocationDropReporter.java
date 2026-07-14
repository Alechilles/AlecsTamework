package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joml.Vector3d;

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
        boolean submitted = submit(
                pending.npcUuid,
                pending.ownerUuid,
                pending.sourceHintPosition,
                pending.alternateSourceHintPosition,
                pending.destination,
                pending.queuedAtMs,
                droppedAtMs,
                pending.retryAttempts
        );
        diagnostic.accept(
                submitted ? Level.INFO : Level.WARNING,
                "Dropped relocation after retries for npc=" + pending.npcUuid
                        + ", retries=" + pending.retryAttempts
                        + ", ageMs=" + (droppedAtMs - pending.queuedAtMs)
                        + ", lostTransitionSubmitted=" + submitted
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

    void reportWorldRemoval(CommandRelocationNpcTracker.WorldRemovalCandidate candidate,
                            long removedAtMs) {
        boolean submitted = submit(
                candidate.npcUuid(),
                candidate.ownerUuid(),
                candidate.lastKnownPosition(),
                null,
                null,
                removedAtMs,
                removedAtMs,
                0
        );
        diagnostic.accept(
                submitted ? Level.INFO : Level.WARNING,
                "Delete-on-remove world removed companion npc=" + candidate.npcUuid()
                        + ", world=" + candidate.worldName()
                        + ", lostTransitionSubmitted=" + submitted
        );
    }

    private boolean submit(UUID npcUuid,
                           @Nullable UUID ownerUuid,
                           @Nullable Vector3d sourceHintPosition,
                           @Nullable Vector3d alternateSourceHintPosition,
                           @Nullable Vector3d destination,
                           long queuedAtMs,
                           long droppedAtMs,
                           int retryAttempts) {
        CommandRelocationDropListener current = listener;
        if (current == null) {
            return false;
        }
        try {
            return current.onRelocationDropped(
                    npcUuid,
                    ownerUuid,
                    sourceHintPosition,
                    alternateSourceHintPosition,
                    destination,
                    queuedAtMs,
                    droppedAtMs,
                    retryAttempts
            );
        } catch (RuntimeException | LinkageError exception) {
            diagnostic.accept(Level.WARNING,
                    "Relocation drop callback failed for npc=" + npcUuid);
            return false;
        }
    }
}
