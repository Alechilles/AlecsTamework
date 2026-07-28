package com.alechilles.alecstamework.companion.dormant;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import javax.annotation.Nonnull;

/**
 * Positive source evidence that one formerly live companion entered a dormant state.
 *
 * <p>The vocabulary intentionally has no absence or timeout value. Death is proven by the saved
 * death component; lost state is proven only by an authoritative destructive-removal event.</p>
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

    /** Authoritative positive event classes accepted by the dormant transition protocol. */
    public enum Kind {
        DEATH_COMPONENT(LifecycleState.DEAD_REVIVABLE, new SnapshotKind("death")),
        DESTRUCTIVE_REMOVAL(LifecycleState.LOST, new SnapshotKind("lost")),
        WORLD_DELETION(LifecycleState.LOST, new SnapshotKind("lost"));

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
