package com.alechilles.alecstamework.api;

/** Stable outcome categories for durable command-family roster mutations. */
public enum CommandFamilyRosterMutationStatus {
    APPLIED,
    IDEMPOTENT,
    CONFLICT,
    NOT_FOUND,
    UNAVAILABLE,
    FAILED
}
