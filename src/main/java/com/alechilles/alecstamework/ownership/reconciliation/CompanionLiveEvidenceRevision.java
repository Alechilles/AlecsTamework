package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic fence for live companion evidence that a detached saved-world scan cannot observe.
 *
 * <p>Online inventory events and semantic NPC observations share this revision. Startup recovery
 * captures it before scanning and must still observe the same value after its final canonical
 * reload before absence evidence can authorize replay.</p>
 */
public final class CompanionLiveEvidenceRevision {
    private final AtomicLong revision = new AtomicLong();

    /** Returns the current revision for a later fail-closed stability check. */
    public long capture() {
        return revision.get();
    }

    /** Confirms that no tracked live evidence changed after {@code expectedRevision}. */
    public boolean isCurrent(long expectedRevision) {
        return expectedRevision >= 0L && revision.get() == expectedRevision;
    }

    /** Advances the fence after one live evidence mutation. */
    public long advance() {
        while (true) {
            long current = revision.get();
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("live companion evidence revision exhausted");
            }
            long advanced = current + 1L;
            if (revision.compareAndSet(current, advanced)) {
                return advanced;
            }
        }
    }
}
