package com.alechilles.alecstamework.persistence.projection;

import javax.annotation.Nonnull;

/**
 * After-commit projection consumer that must compare aggregate revisions for duplicate delivery.
 */
public interface ProjectionConsumer {
    @Nonnull
    ProjectionConsumerId consumerId();

    /** Returns the event types this consumer needs from the shared outbox. */
    @Nonnull
    default ProjectionSubscription subscription() {
        return ProjectionSubscription.allEvents();
    }

    @Nonnull
    ProjectionApplyOutcome apply(@Nonnull ProjectionEvent event) throws Exception;

    /**
     * Applies an event with its explicit publication origin.
     *
     * <p>Existing state projections are context-independent and continue
     * through the one-argument method. Semantic event bridges override this
     * method when recovery is part of their public contract.</p>
     */
    @Nonnull
    default ProjectionApplyOutcome apply(
            @Nonnull ProjectionEvent event,
            @Nonnull ProjectionPublicationContext context
    ) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException(
                    "Projection publication context is required"
            );
        }
        return apply(event);
    }
}
