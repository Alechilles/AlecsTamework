package com.alechilles.alecstamework.persistence.projection;

import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/** Immutable event-type routing contract for one projection consumer. */
public record ProjectionSubscription(
        boolean wildcard,
        @Nonnull Set<ProjectionEventType> eventTypes
) {
    public ProjectionSubscription {
        if (eventTypes == null) {
            throw new IllegalArgumentException("Projection event types are required");
        }
        eventTypes = Set.copyOf(eventTypes);
        if (wildcard != eventTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Use wildcard with no types or typed delivery with types"
            );
        }
    }

    /** Creates a typed subscription for one or more event types. */
    @Nonnull
    public static ProjectionSubscription events(
            @Nonnull Set<ProjectionEventType> eventTypes
    ) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "A typed projection subscription needs event types"
            );
        }
        return new ProjectionSubscription(false, eventTypes);
    }

    /** Creates a subscription that receives every outbox event. */
    @Nonnull
    public static ProjectionSubscription allEvents() {
        return new ProjectionSubscription(true, Set.of());
    }

    /** Returns whether this subscription routes the supplied event type. */
    public boolean accepts(@Nonnull ProjectionEventType eventType) {
        return wildcard || eventTypes.contains(
                Objects.requireNonNull(eventType, "Projection event type")
        );
    }
}
