package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/** Idempotent handle for one Activity API V2 feed subscription. */
public interface ActivityFeedSubscription extends AutoCloseable {
    /** Returns the consumer identity bound to this subscription. */
    @Nonnull
    String consumerId();

    /**
     * Closes the subscription. Repeated calls are safe. Closing blocks new callbacks but does not
     * cancel a callback that is already in flight.
     */
    @Override
    void close();
}
