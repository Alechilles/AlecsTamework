package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Root command for optional NPC template patch diagnostics.
 */
public final class TameworkTemplatePatchesCommand extends AbstractCommandCollection {
    public TameworkTemplatePatchesCommand() {
        super("templatepatches", "Inspect and reload optional NPC template patches.");
        addSubCommand(new TameworkTemplatePatchesStatusCommand());
        addSubCommand(new TameworkTemplatePatchesReloadCommand());
    }
}
