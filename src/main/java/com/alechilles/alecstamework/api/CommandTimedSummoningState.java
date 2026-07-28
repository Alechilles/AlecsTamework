package com.alechilles.alecstamework.api;

/** Public projection of a command-roster member's durable summon lifecycle. */
public enum CommandTimedSummoningState {
    ROSTER_STORED,
    RESTORING,
    ACTIVE,
    UNLOADED,
    STORING,
    DEAD_REVIVABLE,
    LOST
}
