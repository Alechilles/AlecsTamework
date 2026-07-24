package com.alechilles.alecstamework.damage;

/** Lifecycle and compatibility state for the optional SimpleClaims damage bridge. */
enum SimpleClaimsPluginState {
    READY,
    ABSENT,
    DISABLED,
    NOT_READY,
    INCOMPATIBLE,
    ERROR
}
