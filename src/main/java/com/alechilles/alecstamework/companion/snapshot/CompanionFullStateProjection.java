package com.alechilles.alecstamework.companion.snapshot;

/**
 * Stable codec identity for a spawn-ready, source-neutral companion projection.
 */
public final class CompanionFullStateProjection {
    public static final SnapshotKind KIND =
            new SnapshotKind("full_state_projection");
    public static final int VERSION = 1;

    private CompanionFullStateProjection() {
    }
}
