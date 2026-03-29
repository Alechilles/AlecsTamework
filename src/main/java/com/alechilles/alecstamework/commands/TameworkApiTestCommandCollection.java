package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * `/tw api test` command collection.
 */
public final class TameworkApiTestCommandCollection extends AbstractCommandCollection {
    public TameworkApiTestCommandCollection() {
        super("test", "Run or manage Tamework API self-tests.");
        addSubCommand(new TameworkApiTestPrepareCommand());
        addSubCommand(new TameworkApiTestStatusCommand());
        addSubCommand(new TameworkApiTestRunCommand());
        addSubCommand(new TameworkApiTestResetCommand());
    }
}
