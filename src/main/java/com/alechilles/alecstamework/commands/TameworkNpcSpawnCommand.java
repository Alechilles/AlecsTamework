package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups NPC spawn commands. */
public final class TameworkNpcSpawnCommand extends AbstractCommandCollection {
    public TameworkNpcSpawnCommand() {
        super("spawn", "server.tamework.commands.npcSpawn.description");
        addSubCommand(new TameworkNpcSpawnTamedCommand());
    }
}
