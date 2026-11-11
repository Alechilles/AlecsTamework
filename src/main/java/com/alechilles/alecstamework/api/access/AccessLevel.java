package com.alechilles.alecstamework.api.access;

import javax.annotation.Nonnull;

/**
 * How much a player may do with a companion. The levels are ordered: each one includes everything
 * the levels before it allow. An owner can grant {@link #CARETAKER}, {@link #HANDLER} or
 * {@link #MANAGER}; {@link #OWNER} belongs to the owner alone.
 */
public enum AccessLevel {
    /** No access: the player is treated like any stranger. */
    NONE,
    /** Feed, water, pet and heal, and see the companion's status. */
    CARETAKER,
    /** Also harvest, mount, command, recall and breed, and see the companion in panels. */
    HANDLER,
    /** Also rename, cull and use coops. */
    MANAGER,
    /** The owner: also capture, release, transfer and share. */
    OWNER;

    /** True when this level is {@code other} or higher. */
    public boolean atLeast(@Nonnull AccessLevel other) {
        return compareTo(other) >= 0;
    }

    /** True for the levels an owner can give to someone else. */
    public boolean isGrantable() {
        return this == CARETAKER || this == HANDLER || this == MANAGER;
    }

    /** The lower of two levels. */
    @Nonnull
    public static AccessLevel lowerOf(@Nonnull AccessLevel first, @Nonnull AccessLevel second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
