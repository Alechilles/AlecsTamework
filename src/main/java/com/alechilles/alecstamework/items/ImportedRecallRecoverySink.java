package com.alechilles.alecstamework.items;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Receives one terminal failure from an explicitly requested unloaded Recall.
 *
 * <p>The terminal service supplies this only after clean retry exhaustion and
 * before any physical mutation. Implementations must recheck the exact
 * profile, alias, owner, lifecycle, and snapshot fences.</p>
 */
@FunctionalInterface
public interface ImportedRecallRecoverySink {
    ImportedRecallRecoverySink NOOP = failure ->
            CompletableFuture.completedFuture(RecoveryOutcome.NONE);

    @Nonnull
    CompletionStage<RecoveryOutcome> recover(@Nonnull RecallFailure failure);

    /** Terminal action authorized by the durable recovery result. */
    enum RecoveryOutcome {
        NONE,
        RECOVERED,
        RETRY_REQUIRED
    }

    /** Immutable player-intent and relocation timing evidence. */
    record RecallFailure(
            @Nonnull UUID npcUuid,
            @Nonnull UUID ownerUuid,
            long queuedAtMs,
            long failedAtMs,
            String probedWorldName
    ) {
        public RecallFailure {
            if (npcUuid == null || ownerUuid == null
                    || queuedAtMs > failedAtMs) {
                throw new IllegalArgumentException(
                        "Complete ordered recall failure evidence is required"
                );
            }
            probedWorldName = probedWorldName == null
                    || probedWorldName.isBlank()
                    ? null : probedWorldName.trim();
        }

        public RecallFailure(
                UUID npcUuid,
                UUID ownerUuid,
                long queuedAtMs,
                long failedAtMs
        ) {
            this(npcUuid, ownerUuid, queuedAtMs, failedAtMs, null);
        }
    }
}
