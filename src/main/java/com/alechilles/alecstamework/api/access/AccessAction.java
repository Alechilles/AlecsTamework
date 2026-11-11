package com.alechilles.alecstamework.api.access;

import javax.annotation.Nonnull;

/** What a player wants to do with a companion, and the lowest {@link AccessLevel} that may do it. */
public enum AccessAction {
    /** Feed, water, pet, heal, and read the companion's status. */
    CARE(AccessLevel.CARETAKER),
    /** Take a harvest such as milk or wool. */
    HARVEST(AccessLevel.HANDLER),
    /** Mount, shoulder-ride or toggle flight. */
    MOUNT(AccessLevel.HANDLER),
    /** Link a command item, give commands, lead a follow, recall, and set or return home. */
    COMMAND(AccessLevel.HANDLER),
    /** Start breeding. */
    BREED(AccessLevel.HANDLER),
    /** See the companion in companion panels. */
    VIEW_IN_PANELS(AccessLevel.HANDLER),
    /** Rename. */
    RENAME(AccessLevel.MANAGER),
    /** Cull, by interaction, panel action or kill. */
    CULL(AccessLevel.MANAGER),
    /** Put the companion in a coop or take it out. */
    COOP(AccessLevel.MANAGER),
    /** Capture into an item, release, pay for a revive, transfer, and change sharing. */
    OWNER_ONLY(AccessLevel.OWNER);

    private final AccessLevel minimumLevel;

    AccessAction(@Nonnull AccessLevel minimumLevel) {
        this.minimumLevel = minimumLevel;
    }

    /** The lowest level that may do this. */
    @Nonnull
    public AccessLevel minimumLevel() {
        return minimumLevel;
    }

    /** True when a player holding {@code level} may do this. */
    public boolean allowedAt(@Nonnull AccessLevel level) {
        return level.atLeast(minimumLevel);
    }
}
