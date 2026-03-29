package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * `/tw api` command collection.
 */
public final class TameworkApiCommandCollection extends AbstractCommandCollection {
    public TameworkApiCommandCollection() {
        super("api", "Tamework API tooling commands.");
        addSubCommand(new TameworkApiTestCommandCollection());
    }
}
