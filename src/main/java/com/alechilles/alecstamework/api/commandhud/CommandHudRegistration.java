package com.alechilles.alecstamework.api.commandhud;

import javax.annotation.Nonnull;

/** Exact-generation close handle for one command HUD registration. */
public interface CommandHudRegistration extends AutoCloseable {
    /** Returns the normalized namespaced ID owned by this handle. */
    @Nonnull
    String id();

    /** Returns the registry generation assigned to this registration. */
    long generation();

    /** Returns whether this exact generation remains live. */
    boolean active();

    /** Removes this generation. The operation is idempotent. */
    @Override
    void close();
}
