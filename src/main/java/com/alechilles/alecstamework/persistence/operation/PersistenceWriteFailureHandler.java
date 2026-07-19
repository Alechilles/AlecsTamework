package com.alechilles.alecstamework.persistence.operation;

import java.util.List;
import javax.annotation.Nonnull;

/** Passive boundary for classifying write execution and publication failures outside the queue. */
public interface PersistenceWriteFailureHandler {
    void rolledBack(@Nonnull PersistenceOperationMetadata metadata, @Nonnull Throwable failure);

    void commitOutcomeUnknown(@Nonnull List<PersistenceOperationMetadata> metadata,
                              @Nonnull Throwable failure);

    void publicationFailed(@Nonnull PersistenceOperationMetadata metadata, @Nonnull Throwable failure);

    PersistenceWriteFailureHandler NO_OP = new PersistenceWriteFailureHandler() {
        @Override
        public void rolledBack(PersistenceOperationMetadata metadata, Throwable failure) { }

        @Override
        public void commitOutcomeUnknown(List<PersistenceOperationMetadata> metadata, Throwable failure) { }

        @Override
        public void publicationFailed(PersistenceOperationMetadata metadata, Throwable failure) { }
    };
}
