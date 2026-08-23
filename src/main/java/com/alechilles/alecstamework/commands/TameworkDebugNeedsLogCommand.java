package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups needs debug logging switches. */
public final class TameworkDebugNeedsLogCommand extends AbstractCommandCollection {
    public TameworkDebugNeedsLogCommand() {
        super("needs", "Needs debug logging commands.");
        addSubCommand(new TameworkDebugNeedsConsumeCommand());
        addSubCommand(new TameworkDebugNeedsDamageCommand());
        addSubCommand(new TameworkDebugNeedsSeekCommand());
    }
}
