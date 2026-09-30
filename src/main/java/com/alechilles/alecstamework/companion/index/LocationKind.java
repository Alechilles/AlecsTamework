package com.alechilles.alecstamework.companion.index;

/** Who holds a companion. Only {@link #LIVE} companions have a body in a world. */
public enum LocationKind {
    LIVE, ITEM, COOP, STORED, DEAD, LOST, RELEASED;

    /** Released tombstones only block adoption of old bodies; they never count as owned. */
    public boolean countsAsOwned() {
        return this != RELEASED;
    }
}
