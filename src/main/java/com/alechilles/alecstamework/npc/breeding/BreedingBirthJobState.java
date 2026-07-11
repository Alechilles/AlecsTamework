package com.alechilles.alecstamework.npc.breeding;

/**
 * Lifecycle state for an in-memory breeding birth job.
 *
 * <p>Only the registry may move a job into {@link #SPAWNING}; terminal states are immutable.
 */
public enum BreedingBirthJobState {
    RESERVED(false),
    APPROACHING(false),
    HEARTS_SHOWN(false),
    SPAWNING(false),
    COMPLETED(true),
    CANCELLED(true),
    FAILED(true);

    private final boolean terminal;

    BreedingBirthJobState(boolean terminal) {
        this.terminal = terminal;
    }

    /** Returns whether no further state transition is permitted. */
    public boolean isTerminal() {
        return terminal;
    }

    boolean mayAdvanceTo(BreedingBirthJobState next) {
        return (this == RESERVED && next == APPROACHING)
                || (this == APPROACHING && next == HEARTS_SHOWN);
    }
}
