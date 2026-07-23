package com.alechilles.alecstamework.items.persistence;

/**
 * Immutable, engine-neutral three-dimensional vector stored in persistence snapshots.
 */
public record SnapshotVector3(double x, double y, double z) {
    public SnapshotVector3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Snapshot vector coordinates must be finite");
        }
    }
}
