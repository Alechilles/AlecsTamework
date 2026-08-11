package com.alechilles.alecstamework.items;

import java.util.UUID;
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
    ImportedRecallRecoverySink NOOP = failure -> {
    };

    void recover(@Nonnull RecallFailure failure);

    /** Immutable player-intent and relocation timing evidence. */
    record RecallFailure(
            @Nonnull UUID npcUuid,
            @Nonnull UUID ownerUuid,
            long queuedAtMs,
            long failedAtMs
    ) {
        public RecallFailure {
            if (npcUuid == null || ownerUuid == null
                    || queuedAtMs > failedAtMs) {
                throw new IllegalArgumentException(
                        "Complete ordered recall failure evidence is required"
                );
            }
        }
    }
}
