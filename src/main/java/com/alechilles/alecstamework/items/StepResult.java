package com.alechilles.alecstamework.items;

/**
 * Result wrapper for applying a command step.
 */
final class StepResult {
    final boolean applied;
    final boolean abortAll;

    StepResult(boolean applied, boolean abortAll) {
        this.applied = applied;
        this.abortAll = abortAll;
    }
}
