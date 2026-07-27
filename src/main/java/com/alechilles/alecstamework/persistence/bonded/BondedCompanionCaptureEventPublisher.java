package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionCaptureResolvedEvent;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Replays committed bonded capture notifications from bounded operation evidence. */
public final class BondedCompanionCaptureEventPublisher {
    private final BondedCompanionCaptureEvidenceStore evidence;
    private final Consumer<BondedCompanionCaptureResolvedEvent> events;
    private final LongSupplier clock;

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
    public int publishPending(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        int published = 0;
        for (BondedCompanionCaptureEvidence capture
                : evidence.listUnpublishedCaptureEvidence(limit)) {
            long emittedAtMs = clock.getAsLong();
            try {
                events.accept(new BondedCompanionCaptureResolvedEvent(
                        capture.toView(), emittedAtMs));
            } catch (RuntimeException | LinkageError failure) {
                continue;
            }
            if (evidence.markCaptureEvidencePublished(
                    capture, emittedAtMs)) {
                published++;
            }
        }
        return published;
    }
}
