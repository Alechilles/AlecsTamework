package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

/**
 * Publishes profile-scoped conflict-free proofs only after the corresponding reconciliation pass
 * has crossed its final canonical reload and coverage fence.
 */
public final class ReconciliationEvidenceRecoveryProofRegistry {
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    /** Stages a complete scan result; it is not usable for quarantine clearing until sealed. */
    public void stage(@Nonnull String scanEpoch, @Nonnull Set<String> conflictFreeProfiles) {
        snapshot.set(new Snapshot(scanEpoch, Set.copyOf(conflictFreeProfiles), false));
    }

    /** Makes only the matching staged scan available to scoped recovery. */
    public void seal(@Nonnull String scanEpoch) {
        snapshot.updateAndGet(current -> current.scanEpoch().equals(scanEpoch)
                ? new Snapshot(current.scanEpoch(), current.conflictFreeProfiles(), true)
                : current);
    }

    /** Revokes an in-flight or previously sealed proof when finalization fails. */
    public void invalidate(@Nonnull String scanEpoch) {
        snapshot.updateAndGet(current -> current.scanEpoch().equals(scanEpoch)
                ? Snapshot.empty()
                : current);
    }

    public boolean isSealedConflictFree(@Nonnull String profileId) {
        Snapshot current = snapshot.get();
        return current.sealed() && current.conflictFreeProfiles().contains(profileId);
    }

    record Snapshot(@Nonnull String scanEpoch,
                    @Nonnull Set<String> conflictFreeProfiles,
                    boolean sealed) {
        private static Snapshot empty() {
            return new Snapshot("<none>", Set.of(), false);
        }
    }
}
