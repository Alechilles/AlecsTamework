package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups growth and aging debug commands that act on selected NPCs over time. */
public final class TameworkDebugGrowthCommand extends AbstractCommandCollection {
    public TameworkDebugGrowthCommand() {
        super("growth", "server.tamework.commands.debugGrowth.description");
        addSubCommand(new TameworkAdvanceGrowthCommand());
    }
}
