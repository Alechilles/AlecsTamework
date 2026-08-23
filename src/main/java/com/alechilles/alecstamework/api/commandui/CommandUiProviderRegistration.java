package com.alechilles.alecstamework.api.commandui;

import javax.annotation.Nonnull;

/**
 * Idempotent handle for one command UI provider registration generation.
 *
 * <p>Closing this handle removes only its exact provider generation. A
 * replacement provider can then register under the same identifier without
 * affecting the old handle.</p>
 */
public interface CommandUiProviderRegistration extends AutoCloseable {
    /** Returns the normalized provider identifier owned by this handle. */
    @Nonnull
    CommandUiProviderId providerId();

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
