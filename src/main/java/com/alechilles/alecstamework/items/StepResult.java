package com.alechilles.alecstamework.items;

/**
 * Result wrapper for applying a command step.
 */
final class StepResult {
    final boolean applied;
    final boolean abortAll;
    final RelocationState appliedState;

    StepResult(boolean applied, boolean abortAll) {
        this(applied, abortAll, null);
    }

    StepResult(boolean applied, boolean abortAll, RelocationState appliedState) {
        this.applied = applied;
        this.abortAll = abortAll;
        this.appliedState = appliedState;
    }
}
