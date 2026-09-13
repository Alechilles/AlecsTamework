package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Groups breeding debug mutations.
 */
public final class TameworkDebugSetBreedingCommand extends AbstractCommandCollection {
    public TameworkDebugSetBreedingCommand() {
        super("breeding", "server.tamework.commands.debugSetBreeding.description");
        addSubCommand(new TameworkSetBreedingReadyCommand());
    }
}
