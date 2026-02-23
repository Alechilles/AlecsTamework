package com.alechilles.alecstamework.items;

/**
 * Post-relocation state/sub-state pair for queued companion teleportation.
 */
final class RelocationState {
    final String state;
    final String subState;

    RelocationState(String state, String subState) {
        this.state = state;
        this.subState = subState;
    }
}
