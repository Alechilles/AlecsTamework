package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Groups debug mutations by the NPC state they change.
 */
public final class TameworkDebugSetCommand extends AbstractCommandCollection {
    public TameworkDebugSetCommand() {
        super("set", "Set debug NPC state.");
        addSubCommand(new TameworkSetNeedsCommand());
        addSubCommand(new TameworkSetHungerCommand());
        addSubCommand(new TameworkSetThirstCommand());
        addSubCommand(new TameworkDebugSetBreedingCommand());
    }
}
