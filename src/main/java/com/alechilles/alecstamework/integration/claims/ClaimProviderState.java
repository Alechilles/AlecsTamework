package com.alechilles.alecstamework.integration.claims;

/**
 * Lifecycle and compatibility state for an optional claim provider.
 */
public enum ClaimProviderState {
    READY,
    ABSENT,
    DISABLED,
    NOT_READY,
    INCOMPATIBLE,
    ERROR,
    INVALID,
    OFF
}
