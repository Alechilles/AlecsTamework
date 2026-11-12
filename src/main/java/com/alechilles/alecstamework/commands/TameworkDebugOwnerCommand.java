package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups debug commands that act on an owner's clocks rather than on one NPC. */
public final class TameworkDebugOwnerCommand extends AbstractCommandCollection {
    public TameworkDebugOwnerCommand() {
        super("owner", "server.tamework.commands.debugOwner.description");
        addSubCommand(new TameworkOwnerOfflineAgeCommand());
    }
}
