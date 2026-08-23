package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups administrative NPC commands. */
public final class TameworkNpcCommand extends AbstractCommandCollection {
    public TameworkNpcCommand() {
        super("npc", "Tamework NPC commands.");
        addSubCommand(new TameworkFindNpcCommand());
        addSubCommand(new TameworkNpcSpawnCommand());
        addSubCommand(new TameworkNpcCleanCommand());
    }
}
