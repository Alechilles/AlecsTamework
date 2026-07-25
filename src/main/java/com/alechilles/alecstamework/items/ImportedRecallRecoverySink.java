package com.alechilles.alecstamework.items;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Receives one terminal failure from an explicitly requested unloaded recall.
 *
 * <p>Implementations may recover only importer-authored, single-use evidence;
 * a normal relocation failure must remain non-authoritative.</p>
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
