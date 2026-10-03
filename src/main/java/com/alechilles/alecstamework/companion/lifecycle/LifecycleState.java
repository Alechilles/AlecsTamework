package com.alechilles.alecstamework.companion.lifecycle;

import javax.annotation.Nonnull;

/** Sole durable lifecycle vocabulary for replacement companion persistence. */
public enum LifecycleState {
    ACTIVE(LifecycleLocationKind.LIVE_ENTITY),
    UNLOADED(LifecycleLocationKind.NONE),
    CAPTURED(LifecycleLocationKind.CAPTURE_ITEM),
    COOP(LifecycleLocationKind.COOP_SLOT),
    ROSTER_STORED(LifecycleLocationKind.COMMAND_ROSTER),
    PROVISIONED_DORMANT(LifecycleLocationKind.PROVISIONING),
    DEAD_REVIVABLE(LifecycleLocationKind.NONE),
    LOST(LifecycleLocationKind.NONE),
    RELEASED(LifecycleLocationKind.NONE),
    UNRESOLVED(LifecycleLocationKind.UNRESOLVED);

    private final LifecycleLocationKind requiredLocation;

    LifecycleState(LifecycleLocationKind requiredLocation) {
        this.requiredLocation = requiredLocation;
    }

    /** Returns the only location category valid for this state. */
    @Nonnull
    public LifecycleLocationKind requiredLocation() {
        return requiredLocation;
    }
}
