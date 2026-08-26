package com.alechilles.alecstamework.api.commandhud;

import java.util.Set;

/** The independently selectable command HUD presentation surfaces. */
public enum CommandHudSurface {
    /** The HUD that presents information about the current command target. */
    TARGET,
    /** The HUD that presents the equipped command hotswap controls. */
    HOTSWAP;

    /** Returns whether this surface is the target HUD. */
    public boolean isTarget() {
        return this == TARGET;
    }

    /** Returns whether this surface is the hotswap HUD. */
    public boolean isHotswap() {
        return this == HOTSWAP;
    }

    /** Returns both supported surfaces as an immutable set. */
    public static Set<CommandHudSurface> all() {
        return Set.of(values());
    }
}
