package com.alechilles.alecstamework.persistence.projection;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;

/** Complete transaction-local outbox event before SQLite assigns its monotonic sequence. */
public record ProjectionEventDraft(@Nonnull OperationId operationId,
                                   @Nonnull ProjectionEventType eventType,
                                   @Nonnull String aggregateId,
                                   long aggregateRevision,
                                   int payloadVersion,
                                   @Nonnull String payloadJson,
                                   long createdAtMs) {
    public ProjectionEventDraft {
        if (operationId == null || eventType == null) {
            throw new IllegalArgumentException("Projection operation and event type are required");
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
