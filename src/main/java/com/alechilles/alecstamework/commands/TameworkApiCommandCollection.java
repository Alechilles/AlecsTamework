package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * `/tw api` command collection.
 */
public final class TameworkApiCommandCollection extends AbstractCommandCollection {
    public TameworkApiCommandCollection() {
        super("api", "server.tamework.commands.api.description");
        addSubCommand(new TameworkApiTestCommandCollection());
    }
}
