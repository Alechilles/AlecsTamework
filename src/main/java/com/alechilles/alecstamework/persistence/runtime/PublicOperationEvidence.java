package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.List;
import javax.annotation.Nonnull;

/** Adapter-neutral durable operation envelope and committed event evidence. */
public record PublicOperationEvidence(
        @Nonnull OperationEnvelope operation,
        @Nonnull List<ProjectionEvent> events
) {
    public PublicOperationEvidence {
        if (operation == null || events == null
                || events.stream().anyMatch(event ->
                !operation.operationId().equals(event.operationId()))) {
            throw new IllegalArgumentException(
                    "Complete operation-scoped evidence is required"
            );
        }
        events = List.copyOf(events);
    }
}
