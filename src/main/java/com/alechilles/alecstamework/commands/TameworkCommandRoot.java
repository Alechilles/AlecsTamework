package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Root /tw command dispatcher.
 */
public final class TameworkCommandRoot extends AbstractCommandCollection {
    public TameworkCommandRoot() {
        super("tw", "Tamework commands.");
        addSubCommand(new TameworkSetOwnerCommand());
        addSubCommand(new TameworkGetOwnerCommand());
        addSubCommand(new TameworkGetTamedCommand());
        addSubCommand(new TameworkSetTamedCommand());
        addSubCommand(new TameworkGetAlarmCommand());
        addSubCommand(new TameworkReloadConfigCommand());
    }
}
