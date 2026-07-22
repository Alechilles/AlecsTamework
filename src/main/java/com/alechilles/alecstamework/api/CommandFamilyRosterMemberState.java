package com.alechilles.alecstamework.api;

/** Durable command-roster disposition; separate from population activity and bulk selection. */
public enum CommandFamilyRosterMemberState {
    ROSTER_STORED,
    RESTORING,
    ACTIVE,
    UNLOADED,
    STORING,
    DEAD_REVIVABLE,
    LOST
}
