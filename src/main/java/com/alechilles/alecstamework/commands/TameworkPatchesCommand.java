package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Root command for optional patch diagnostics.
 */
public final class TameworkPatchesCommand extends AbstractCommandCollection {
    public TameworkPatchesCommand() {
        super("patches", "Inspect and reload optional Tamework patches.");
        addSubCommand(new TameworkPatchesStatusCommand());
        addSubCommand(new TameworkPatchesReloadCommand());
    }
}
