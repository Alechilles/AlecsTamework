package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups companion debug logging switches. */
public final class TameworkDebugCompanionLogCommand extends AbstractCommandCollection {
    public TameworkDebugCompanionLogCommand() {
        super("companion", "Companion debug logging commands.");
        addSubCommand(new TameworkDebugFlyingCompanionCommand());
    }
}
