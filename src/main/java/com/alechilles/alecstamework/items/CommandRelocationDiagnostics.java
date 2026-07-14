package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nullable;

/** Owns throttled relocation diagnostics so the relocation service remains orchestration-only. */
final class CommandRelocationDiagnostics {
    private static final long SLOW_OPERATION_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(20L);
    private static final int RETRY_PROGRESS_LOG_STEP = 5;

    @Nullable
    private final HytaleLogger logger;

    CommandRelocationDiagnostics(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    boolean isLagDebugEnabled() {
        Tamework plugin = Tamework.getInstance();
        return plugin != null && plugin.isDebugLagEnabled();
    }

    void log(Level level, String message) {
        if (logger == null || message == null || message.isBlank()) {
            return;
        }
        try {
            logger.at(level).log("[CompanionTravel] " + message);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never interrupt relocation terminality.
        }
    }

    void logSlowOperation(long startedNs, String operation) {
        if (startedNs <= 0L || logger == null) {
            return;
        }
        long elapsedNs = System.nanoTime() - startedNs;
        if (elapsedNs < SLOW_OPERATION_THRESHOLD_NS) {
            return;
        }
        log(Level.WARNING, "Tamework lag probe: " + operation + " took "
                + (elapsedNs / 1_000_000.0) + "ms.");
    }

    void logRetryProgress(PendingRelocation pending, long nowMs) {
        if (pending == null || logger == null || !isLagDebugEnabled()
                || !pending.markRetryProgressLogged(RETRY_PROGRESS_LOG_STEP)) {
            return;
        }
        log(Level.INFO, "Tamework lag probe: relocation still pending (npc="
                + pending.npcUuid + ", retries=" + pending.retryAttempts
                + ", ageMs=" + (nowMs - pending.queuedAtMs) + ").");
    }

    void chunkLeaseNotRetained(UUID npcUuid, int chunkX, int chunkZ) {
        if (!isLagDebugEnabled()) {
            return;
        }
        log(Level.WARNING, "Tamework lag probe: relocation chunk lease was not retained (npc="
                + npcUuid + ", chunkX=" + chunkX + ", chunkZ=" + chunkZ + ").");
    }

    void chunkRequestFailed(UUID npcUuid, int chunkX, int chunkZ, @Nullable Throwable failure) {
        if (logger == null || !isLagDebugEnabled()) {
            return;
        }
        try {
            var event = logger.at(Level.WARNING);
            if (failure != null) {
                event = event.withCause(failure);
            }
            event.log("Tamework lag probe: relocation chunk request failed (npc="
                    + npcUuid + ", chunkX=" + chunkX + ", chunkZ=" + chunkZ + ").");
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never interrupt chunk-load retry handling.
        }
    }
}
