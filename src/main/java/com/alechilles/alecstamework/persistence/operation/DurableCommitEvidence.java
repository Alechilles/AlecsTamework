package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.List;
import javax.annotation.Nonnull;

/** Exact durable-or-published operation envelope and its immutable committed outbox rows. */
public record DurableCommitEvidence(@Nonnull OperationEnvelope operation,
                                    @Nonnull List<ProjectionEvent> events) {
    public DurableCommitEvidence {
        if (operation == null
                || (operation.phase() != OperationPhase.DURABLE
                && operation.phase() != OperationPhase.PUBLISHED)
                || events == null
                || events.isEmpty()) {
            throw new IllegalArgumentException(
                    "Durable lineage and nonempty outbox evidence are required"
            );
        }
        events = List.copyOf(events);
        for (ProjectionEvent event : events) {
            if (event == null || !event.operationId().equals(operation.operationId())) {
                throw new IllegalArgumentException("Outbox evidence must belong to the durable operation");
            }
        }
    }

    /** Returns the highest committed event sequence for after-commit projection. */
    @Nonnull
    public com.alechilles.alecstamework.persistence.projection.ProjectionSequence outboxHead() {
        return events.getLast().sequence();
    }
}
