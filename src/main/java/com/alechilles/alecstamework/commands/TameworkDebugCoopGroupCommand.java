package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups coop debug commands that act on one coop block. */
public final class TameworkDebugCoopGroupCommand extends AbstractCommandCollection {
    public TameworkDebugCoopGroupCommand() {
        super("coop", "server.tamework.commands.debugCoopGroup.description");
        addSubCommand(new TameworkAdvanceCoopCommand());
    }
}
