package com.alechilles.alecstamework.api.access;

/** The outcome of a request to change what an owner shares. */
public enum AccessChangeResult {
    CHANGED,
    /** The request matched what was already saved. */
    UNCHANGED,
    /** The server has sharing turned off. */
    SHARING_DISABLED,
    /** An owner cannot share with themselves. */
    SELF,
    /** The level cannot be granted, or is above the server's highest allowed level. */
    LEVEL_NOT_ALLOWED,
    /** The owner already shares with the most named players allowed. */
    TOO_MANY_PLAYERS,
    /** No provider is registered for the group. */
    UNKNOWN_GROUP,
    /** Shared access is not available in this runtime. */
    UNAVAILABLE
}
