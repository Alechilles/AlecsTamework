package com.alechilles.alecstamework.runtime;

/** Startup disposition for one durable persistence authority. */
public enum PersistenceActivationMode {
    /** No durable state exists, so no persistence runtime is required. */
    DORMANT,
    /** Existing state is not safe to mutate and is available only for diagnostics. */
    READ_ONLY,
    /** Durable state requires the full writer and recovery authority. */
    ACTIVE
}
