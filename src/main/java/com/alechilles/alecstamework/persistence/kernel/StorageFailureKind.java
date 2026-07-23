package com.alechilles.alecstamework.persistence.kernel;

/**
 * Stable failure categories exposed by replacement persistence boundaries.
 *
 * <p>Callers should branch on these categories and the retryable flag rather than inspecting
 * driver-specific exception text.</p>
 */
public enum StorageFailureKind {
    UNAVAILABLE,
    BUSY,
    TIMEOUT,
    IO,
    CORRUPT,
    SCHEMA,
    DECODE,
    UNKNOWN
}
