package com.alechilles.alecstamework.api.commandui;

import javax.annotation.Nonnull;

/** Exact-generation close handle for one renderer or contributor registration. */
public interface CommandUiRegistration extends AutoCloseable {
    /** Returns the normalized namespaced ID owned by this handle. */
    @Nonnull
    String id();

    /** Returns the registry generation assigned to this registration. */
    long generation();

    /** Returns whether this exact generation remains live. */
    boolean active();

    /** Alias for callers that use the close-state wording. */
    default boolean isActive() {
        return active();
    }

    /** Removes this generation. The operation is idempotent. */
    @Override
    void close();
}
