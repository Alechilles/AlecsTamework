package com.alechilles.alecstamework.persistence.projection;

import java.util.List;
import javax.annotation.Nonnull;

/** One immutable consumer checkpoint, bounded target, and ordered delivery batch. */
public record ProjectionBatch(@Nonnull ProjectionCheckpoint checkpoint,
                              @Nonnull ProjectionSequence target,
                              @Nonnull List<ProjectionEvent> events) {
    public ProjectionBatch {
        if (checkpoint == null || target == null || events == null) {
            throw new IllegalArgumentException("Complete projection batch is required");
        }
        events = List.copyOf(events);
        ProjectionSequence previous = checkpoint.acknowledgedSequence();
        for (ProjectionEvent event : events) {
            if (event == null || event.sequence().compareTo(previous) <= 0
                    || event.sequence().compareTo(target) > 0) {
                throw new IllegalArgumentException(
                        "Projection batch must be ordered within its target"
                );
            }
            previous = event.sequence();
        }
    }

    /** Compatibility alias for callers that named the bounded target a head. */
    @Nonnull
    public ProjectionSequence head() {
        return target;
    }
}
