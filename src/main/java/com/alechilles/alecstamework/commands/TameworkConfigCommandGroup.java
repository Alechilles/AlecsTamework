package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups Tamework configuration commands. */
public final class TameworkConfigCommandGroup extends AbstractCommandCollection {
    public TameworkConfigCommandGroup() {
        super("config", "Tamework configuration commands.");
        addSubCommand(new TameworkConfigCommand());
        addSubCommand(new TameworkReloadConfigCommand());
    }
}
