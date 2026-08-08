package com.alechilles.alecstamework.items;

import javax.annotation.Nullable;

/** Identifiers for non-command actions players may assign to a flute hotswap slot. */
public final class CommandHotswapAction {
    public static final String CYCLE_GROUP = "__cycle_group__";

    private CommandHotswapAction() {
    }

    public static boolean isCycleGroup(@Nullable String value) {
        return CYCLE_GROUP.equalsIgnoreCase(value == null ? "" : value.trim());
    }
}
