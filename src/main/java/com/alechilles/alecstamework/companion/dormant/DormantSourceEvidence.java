package com.alechilles.alecstamework.companion.dormant;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import javax.annotation.Nonnull;

/**
 * Source evidence that one companion entered a dormant state.
 *
 * <p>Death and removal values are positive world observations. Explicit recall
 * exhaustion is a separate player-authorized repair value. It is valid only
 * for an exact unloaded profile after all non-mutating relocation probes end.</p>
 */
public record DormantSourceEvidence(
        @Nonnull NpcAlias sourceAlias,
        @Nonnull String sourceWorldKey,
        @Nonnull Kind kind,
        @Nonnull ReconciliationGeneration observedGeneration,
        @Nonnull String receiptKey,
        long observedAtMs
) {
    public DormantSourceEvidence {
        if (sourceAlias == null || kind == null || observedGeneration == null) {
            throw new IllegalArgumentException("Complete dormant source evidence is required");
        }
        sourceWorldKey = requireText(sourceWorldKey, "Dormant source world");
        receiptKey = requireText(receiptKey, "Dormant source receipt");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    /** Authoritative event or explicit repair classes accepted by the protocol. */
    public enum Kind {
        DEATH_COMPONENT(LifecycleState.DEAD_REVIVABLE, new SnapshotKind("death")),
        DESTRUCTIVE_REMOVAL(LifecycleState.LOST, new SnapshotKind("lost")),
        WORLD_DELETION(LifecycleState.LOST, new SnapshotKind("lost")),
        EXPLICIT_RECALL_EXHAUSTED(LifecycleState.LOST, new SnapshotKind("lost"));

        private final LifecycleState targetState;
        private final SnapshotKind snapshotKind;

        Kind(LifecycleState targetState, SnapshotKind snapshotKind) {
            this.targetState = targetState;
            this.snapshotKind = snapshotKind;
        }

        @Nonnull
        public LifecycleState targetState() {
            return targetState;
        }

        @Nonnull
        public SnapshotKind snapshotKind() {
            return snapshotKind;
        }
    }
}
