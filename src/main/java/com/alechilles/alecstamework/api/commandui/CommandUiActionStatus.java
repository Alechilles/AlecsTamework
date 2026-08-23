package com.alechilles.alecstamework.api.commandui;

/** Stable outcomes returned by a Tamework command UI action invocation. */
public enum CommandUiActionStatus {
    /** The current authority accepted and applied the action. */
    APPLIED,
    /** The current authority dispatched the action but did not confirm a mutation. */
    ACCEPTED,
    /** The action needs a fresh confirmation handle and did not mutate state. */
    CONFIRMATION_REQUIRED,
    /** The handle or current authority does not permit the action. */
    DENIED,
    /** The handle belongs to an older action generation or is no longer live. */
    STALE,
    /** The requested displayed target no longer exists. */
    NOT_FOUND,
    /** The route or optional subsystem is not available. */
    UNAVAILABLE,
    /** The action conflicts with a current state transition. */
    CONFLICT,
    /** The route failed without a more specific stable outcome. */
    FAILED
}
