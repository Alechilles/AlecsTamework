package com.alechilles.alecstamework.api.commandui;

import java.util.EnumSet;
import java.util.Set;

/** Snapshot regions that a provider can update independently. */
public enum CommandUiSection {
    TOOL,
    COMMANDS,
    HOTSWAPS,
    GROUPS,
    ROSTER_STRUCTURE,
    COMPANIONS,
    PANEL,
    ACTIONS,
    GLOBAL_PRESENTATION;

    /** Returns a detached set containing every section. */
    public static Set<CommandUiSection> all() {
        return Set.copyOf(EnumSet.allOf(CommandUiSection.class));
    }
}
