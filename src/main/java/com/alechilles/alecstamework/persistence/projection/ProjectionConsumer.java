package com.alechilles.alecstamework.persistence.projection;

import javax.annotation.Nonnull;

/**
 * After-commit projection consumer that must compare aggregate revisions for duplicate delivery.
 */
public interface ProjectionConsumer {
    @Nonnull
    ProjectionConsumerId consumerId();

    @Nonnull
    ProjectionApplyOutcome apply(@Nonnull ProjectionEvent event) throws Exception;
}
