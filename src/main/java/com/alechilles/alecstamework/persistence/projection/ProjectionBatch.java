package com.alechilles.alecstamework.persistence.projection;

import java.util.List;
import javax.annotation.Nonnull;

/** One immutable consumer checkpoint, outbox head, and ordered delivery batch. */
public record ProjectionBatch(@Nonnull ProjectionCheckpoint checkpoint,
                              @Nonnull ProjectionSequence head,
                              @Nonnull List<ProjectionEvent> events) {
    public ProjectionBatch {
        if (checkpoint == null || head == null || events == null) {
            throw new IllegalArgumentException("Complete projection batch is required");
        }
        events = List.copyOf(events);
        ProjectionSequence previous = checkpoint.acknowledgedSequence();
        for (ProjectionEvent event : events) {
            if (event == null || event.sequence().compareTo(previous) <= 0
                    || event.sequence().compareTo(head) > 0) {
                throw new IllegalArgumentException("Projection batch must be ordered within its head");
            }
            previous = event.sequence();
        }
    }
}
