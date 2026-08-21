package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/** Receives one committed Activity API V2 payload synchronously. */
@FunctionalInterface
public interface ActivityConsumer {
    /** Accepts an activity and returns immediately. */
    void accept(@Nonnull ActivityView activity);
}
