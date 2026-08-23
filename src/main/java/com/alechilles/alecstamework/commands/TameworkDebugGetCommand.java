package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Groups read-only debug NPC state queries.
 */
public final class TameworkDebugGetCommand extends AbstractCommandCollection {
    public TameworkDebugGetCommand() {
        super("get", "Get debug NPC state.");
        addSubCommand(new TameworkGetNeedsCommand());
        addSubCommand(new TameworkGetHappinessCommand());
        addSubCommand(new TameworkGetLifeStageCommand());
        addSubCommand(new TameworkGetTraitsCommand());
        addSubCommand(new TameworkGetTamedCommand());
        addSubCommand(new TameworkGetOwnerCommand());
        addSubCommand(new TameworkGetAlarmCommand());
        addSubCommand(new TameworkGetFlockDebugCommand());
    }
}
