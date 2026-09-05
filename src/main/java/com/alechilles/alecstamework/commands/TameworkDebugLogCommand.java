package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups verbose debug logging switches. */
public final class TameworkDebugLogCommand extends AbstractCommandCollection {
    public TameworkDebugLogCommand() {
        super("log", "Tamework debug logging commands.");
        addSubCommand(new TameworkDebugHookCommand());
        addSubCommand(new TameworkDebugSpawnerCommand());
        addSubCommand(new TameworkDebugSpawnerLocationCommand());
        addSubCommand(new TameworkDebugPromptCommand());
        addSubCommand(new TameworkDebugRideCommand());
        addSubCommand(new TameworkDebugCoopCommand());
        addSubCommand(new TameworkDebugBreedingCommand());
        addSubCommand(new TameworkDebugAvatarFlightCommand());
        addSubCommand(new TameworkDebugDespawnCommand());
        addSubCommand(new TameworkDebugLagCommand());
        addSubCommand(new TameworkDebugTargetHudCommand());
        addSubCommand(new TameworkDebugHarvestCommand());
        addSubCommand(new TameworkDebugRespawnTraceCommand());
        addSubCommand(new TameworkDebugXpEventsCommand());
        addSubCommand(new TameworkDebugNeedsLogCommand());
        addSubCommand(new TameworkDebugCompanionLogCommand());
    }
}
