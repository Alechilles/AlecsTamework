package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Groups client-local debug visual controls. */
public final class TameworkDebugViewCommand extends AbstractCommandCollection {
    public TameworkDebugViewCommand(SpawnBeaconVisualizationService spawnBeaconVisualizationService) {
        super("view", "Tamework debug visualization commands.");
        addSubCommand(new TameworkShowHitboxesCommand());
        addSubCommand(new TameworkShowSpawnBeaconsCommand(spawnBeaconVisualizationService));
        addSubCommand(new TameworkShowSpawnMarkersCommand());
        addSubCommand(new TameworkDeleteSpawnMarkerCommand());
    }
}
