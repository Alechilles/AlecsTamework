package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import javax.annotation.Nullable;

/**
 * Groups Tamework's developer-facing NPC inspection and mutation commands.
 */
public final class TameworkDebugCommand extends AbstractCommandCollection {
    public TameworkDebugCommand(
            SpawnBeaconVisualizationService spawnBeaconVisualizationService,
            @Nullable ReleaseFlow releaseFlow,
            @Nullable CompanionQueries companions
    ) {
        super("debug", "server.tamework.commands.debug.description");
        addSubCommand(new TameworkDebugSetCommand());
        addSubCommand(new TameworkDebugGetCommand());
        addSubCommand(new TameworkDebugLogCommand());
        addSubCommand(new TameworkDebugViewCommand(spawnBeaconVisualizationService));
        addSubCommand(new TameworkDeleteSpawnMarkerCommand());
        addSubCommand(new TameworkDebugClearOwnedCommand(releaseFlow, companions));
        addSubCommand(new TameworkDebugTelemetryCommand());
        addSubCommand(new TameworkDebugAvatarCommand());
    }
}
