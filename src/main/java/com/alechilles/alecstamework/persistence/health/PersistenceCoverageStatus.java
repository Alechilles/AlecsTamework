package com.alechilles.alecstamework.persistence.health;

/** Named authority state for one evidence dimension. */
public enum PersistenceCoverageStatus {
    READY,
    LOADING,
    PARTIAL,
    UNAVAILABLE,
    CONTRADICTORY;

    public boolean globallyReady() {
        return this == READY;
    }
}
