package com.alechilles.alecstamework.companion.live;

/** What the body system does with a companion body that was just added to a world (spec 6.8). */
public enum FenceAction {
    /** The body is the companion's current holder. */
    ACCEPT,
    /** The body is newer than its record; accept it and raise the record's generation. */
    ACCEPT_AND_RAISE,
    /** No record exists at all; create one from this owned, tamed body. */
    ADOPT,
    /** The body is stale, released, orphaned or a duplicate; remove it with REMOVE. */
    REMOVE,
    /** The record could not be read at startup; leave the body untouched. */
    IGNORE
}
