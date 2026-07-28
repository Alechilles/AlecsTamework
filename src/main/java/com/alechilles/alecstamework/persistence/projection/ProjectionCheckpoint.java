package com.alechilles.alecstamework.persistence.projection;

import javax.annotation.Nonnull;

/** Immutable durable acknowledgement for one projection consumer. */
public record ProjectionCheckpoint(@Nonnull ProjectionConsumerId consumerId,
                                   @Nonnull ProjectionSequence acknowledgedSequence,
                                   long updatedAtMs) {
    public ProjectionCheckpoint {
        if (consumerId == null || acknowledgedSequence == null) {
            throw new IllegalArgumentException("Projection consumer and sequence are required");
        }
    }
}
