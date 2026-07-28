package com.alechilles.alecstamework.persistence.projection;

import java.util.List;
import javax.annotation.Nonnull;

/**
 * Fixes an explicit delivery context at a recovery boundary while preserving
 * the delegate's stable checkpoint identity.
 */
public final class ContextualProjectionConsumer implements ProjectionConsumer {
    private final ProjectionConsumer delegate;
    private final ProjectionPublicationContext context;

    public ContextualProjectionConsumer(
            @Nonnull ProjectionConsumer delegate,
            @Nonnull ProjectionPublicationContext context
    ) {
        if (delegate == null || delegate.consumerId() == null
                || context == null) {
            throw new IllegalArgumentException(
                    "Contextual projection consumer dependencies are required"
            );
        }
        this.delegate = delegate;
        this.context = context;
    }

    /** Binds one immutable consumer set to the caller's explicit context. */
    @Nonnull
    public static List<ProjectionConsumer> bind(
            @Nonnull List<? extends ProjectionConsumer> consumers,
            @Nonnull ProjectionPublicationContext context
    ) {
        if (consumers == null || context == null) {
            throw new IllegalArgumentException(
                    "Projection consumers and context are required"
            );
        }
        return consumers.stream()
                .map(consumer -> new ContextualProjectionConsumer(
                        consumer, context
                ))
                .map(ProjectionConsumer.class::cast)
                .toList();
    }

    @Override
    @Nonnull
    public ProjectionConsumerId consumerId() {
        return delegate.consumerId();
    }

    @Override
    @Nonnull
    public ProjectionApplyOutcome apply(@Nonnull ProjectionEvent event)
            throws Exception {
        return delegate.apply(event, context);
    }

    @Override
    @Nonnull
    public ProjectionApplyOutcome apply(
            @Nonnull ProjectionEvent event,
            @Nonnull ProjectionPublicationContext ignored
    ) throws Exception {
        return delegate.apply(event, context);
    }
}
