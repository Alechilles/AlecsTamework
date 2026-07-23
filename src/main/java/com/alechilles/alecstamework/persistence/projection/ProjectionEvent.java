package com.alechilles.alecstamework.persistence.projection;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;

/** Immutable committed outbox event delivered at least once to projection consumers. */
public record ProjectionEvent(@Nonnull ProjectionSequence sequence,
                              @Nonnull OperationId operationId,
                              @Nonnull ProjectionEventType eventType,
                              @Nonnull String aggregateId,
                              long aggregateRevision,
                              int payloadVersion,
                              @Nonnull String payloadJson,
                              long createdAtMs) {
    public ProjectionEvent {
        if (sequence == null || sequence.value() == 0 || operationId == null || eventType == null) {
            throw new IllegalArgumentException("Positive sequence, operation, and event type are required");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("Projection aggregate ID is required");
        }
        aggregateId = aggregateId.trim();
        if (aggregateRevision < 0 || payloadVersion <= 0 || payloadJson == null) {
            throw new IllegalArgumentException("Valid projection revision, version, and JSON are required");
        }
    }
}
