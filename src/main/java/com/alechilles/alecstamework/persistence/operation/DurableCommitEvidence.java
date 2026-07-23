package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.List;
import javax.annotation.Nonnull;

/** Exact durable operation envelope and outbox rows committed in one transaction. */
public record DurableCommitEvidence(@Nonnull OperationEnvelope operation,
                                    @Nonnull List<ProjectionEvent> events) {
    public DurableCommitEvidence {
        if (operation == null || operation.phase() != OperationPhase.DURABLE || events == null
                || events.isEmpty()) {
            throw new IllegalArgumentException("Durable operation and nonempty outbox evidence are required");
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
