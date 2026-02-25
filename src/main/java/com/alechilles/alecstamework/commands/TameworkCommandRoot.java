package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Root /tw command dispatcher.
 */
public final class TameworkCommandRoot extends AbstractCommandCollection {
    public TameworkCommandRoot() {
        super("tw", "Tamework commands.");
        addSubCommand(new TameworkSetOwnerCommand());
        addSubCommand(new TameworkGetOwnerCommand());
        addSubCommand(new TameworkGetHappinessCommand());
        addSubCommand(new TameworkGetNeedsCommand());
        addSubCommand(new TameworkGetLifeStageCommand());
        addSubCommand(new TameworkSetHappinessCommand());
        addSubCommand(new TameworkSetBreedingReadyCommand());
        addSubCommand(new TameworkGetTraitsCommand());
        addSubCommand(new TameworkSetTraitsCommand());
        addSubCommand(new TameworkAddTraitCommand());
        addSubCommand(new TameworkGetTamedCommand());
        addSubCommand(new TameworkSetTamedCommand());
        addSubCommand(new TameworkFindNpcCommand());
        addSubCommand(new TameworkGetAlarmCommand());
        addSubCommand(new TameworkReloadConfigCommand());
        addSubCommand(new TameworkDebugHookCommand());
        addSubCommand(new TameworkDebugSpawnerCommand());
        addSubCommand(new TameworkDebugPromptCommand());
    }
}
