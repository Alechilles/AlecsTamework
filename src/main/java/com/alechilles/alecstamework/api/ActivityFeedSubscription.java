package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/** Idempotent handle for one successful-activity feed subscription. */
public interface ActivityFeedSubscription extends AutoCloseable {
    /** Returns the consumer identity bound to this subscription. */
    @Nonnull
    String consumerId();

    /** Closes the subscription. Repeated calls are safe. */
    @Override
    void close();
}
