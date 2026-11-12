package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups needs debug commands that act on selected NPCs over time. */
public final class TameworkDebugNeedsCommand extends AbstractCommandCollection {
    public TameworkDebugNeedsCommand() {
        super("needs", "server.tamework.commands.debugNeeds.description");
        addSubCommand(new TameworkAdvanceNeedsCommand());
    }
}
