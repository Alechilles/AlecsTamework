package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceDiagnosticExporter;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDiagnosticsReader;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Groups Tamework's developer-facing NPC inspection and mutation commands.
 */
public final class TameworkDebugCommand extends AbstractCommandCollection {
    public TameworkDebugCommand(
            PersistenceDiagnosticsReader persistenceDiagnostics,
            PersistenceDiagnosticExporter persistenceExporter,
            BondedCompanionDiagnosticContributor bondedDiagnostics,
            SpawnBeaconVisualizationService spawnBeaconVisualizationService
    ) {
        super("debug", "Tamework debug commands.");
        addSubCommand(new TameworkDebugSetCommand());
        addSubCommand(new TameworkDebugGetCommand());
        addSubCommand(new TameworkDebugLogCommand());
        addSubCommand(new TameworkDebugViewCommand(spawnBeaconVisualizationService));
        addSubCommand(new TameworkDebugTelemetryCommand());
        addSubCommand(new TameworkDebugPersistenceCommand(
                persistenceDiagnostics, persistenceExporter, bondedDiagnostics
        ));
        addSubCommand(new TameworkDebugAvatarCommand());
    }
}
