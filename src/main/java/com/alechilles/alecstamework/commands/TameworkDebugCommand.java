package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Groups Tamework's developer-facing NPC inspection and mutation commands.
 */
public final class TameworkDebugCommand extends AbstractCommandCollection {
    public TameworkDebugCommand() {
        super("debug", "Tamework debug commands.");
        addSubCommand(new TameworkDebugSetCommand());
        addSubCommand(new TameworkDebugGetCommand());
    }
}
