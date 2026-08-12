package com.alechilles.alecstamework.items;

import java.util.UUID;
import java.util.Set;
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
            String probedWorldName,
            RecallDestination destination,
            @Nonnull Set<RecallSourceSection> completedSourceSections
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
            completedSourceSections = completedSourceSections == null
                    ? Set.of() : Set.copyOf(completedSourceSections);
        }

        public RecallFailure(
                UUID npcUuid,
                UUID ownerUuid,
                long queuedAtMs,
                long failedAtMs,
                String probedWorldName
        ) {
            this(
                    npcUuid, ownerUuid, queuedAtMs, failedAtMs,
                    probedWorldName, null, Set.of()
            );
        }

        public RecallFailure(
                UUID npcUuid,
                UUID ownerUuid,
                long queuedAtMs,
                long failedAtMs
        ) {
            this(
                    npcUuid, ownerUuid, queuedAtMs, failedAtMs,
                    null, null, Set.of()
            );
        }
    }

    /** Frozen placement requested by the explicit Recall. */
    record RecallDestination(
            @Nonnull String worldName,
            double x,
            double y,
            double z
    ) {
        public RecallDestination {
            if (worldName == null || worldName.isBlank()
                    || !Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(z)) {
                throw new IllegalArgumentException(
                        "Complete finite Recall destination is required"
                );
            }
            worldName = worldName.trim();
        }
    }

    /** Exact source section that Hytale completed loading from persistence. */
    record RecallSourceSection(
            @Nonnull String worldName,
            int chunkX,
            int sectionY,
            int chunkZ
    ) {
        public RecallSourceSection {
            if (worldName == null || worldName.isBlank()) {
                throw new IllegalArgumentException(
                        "Recall source world is required"
                );
            }
            worldName = worldName.trim();
        }
    }
}
