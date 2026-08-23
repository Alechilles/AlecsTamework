package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups runtime inspection commands. */
public final class TameworkRuntimeCommand extends AbstractCommandCollection {
    public TameworkRuntimeCommand() {
        super("runtime", "Tamework runtime commands.");
        addSubCommand(new TameworkActivationStatusCommand());
    }
}
