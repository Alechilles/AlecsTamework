package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionCaptureResolvedEvent;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Replays committed notifications from profile-lifetime capture evidence. */
public final class BondedCompanionCaptureEventPublisher {
    private final BondedCompanionCaptureEvidenceStore evidence;
    private final Consumer<BondedCompanionCaptureResolvedEvent> events;
    private final LongSupplier clock;
    private boolean retryRequired = true;

    public BondedCompanionCaptureEventPublisher(
            @Nonnull BondedCompanionCaptureEvidenceStore evidence,
            @Nonnull Consumer<BondedCompanionCaptureResolvedEvent> events,
            @Nonnull LongSupplier clock
    ) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Publishes a bounded batch at least once and checkpoints only after the
     * event sink returns. A crash before checkpoint can safely duplicate the
     * stable operation and attempt identities on the next replay.
     */
    public synchronized int publishPending(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        retryRequired = true;
        List<BondedCompanionCaptureEvidence> pending =
                evidence.listUnpublishedCaptureEvidence(limit);
        int published = 0;
        boolean retry = pending.size() >= limit;
        for (BondedCompanionCaptureEvidence capture : pending) {
            long emittedAtMs = clock.getAsLong();
            try {
                events.accept(new BondedCompanionCaptureResolvedEvent(
                        capture.toView(), emittedAtMs));
            } catch (RuntimeException | LinkageError failure) {
                retry = true;
                continue;
            }
            if (evidence.markCaptureEvidencePublished(
                    capture, emittedAtMs)) {
                published++;
            } else {
                retry = true;
            }
        }
        retryRequired = retry;
        return published;
    }

    /**
     * Retries durable publication only while startup discovery or an earlier
     * incomplete batch says that pending evidence can exist.
     */
    public synchronized int publishPendingIfRequired(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (!retryRequired) return 0;
        return publishPending(limit);
    }
}
