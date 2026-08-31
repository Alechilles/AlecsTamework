package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.persistence.runtime
        .PersistenceDiagnosticsReader;
import com.alechilles.alecstamework.persistence.diagnostics
        .PersistenceDiagnosticExporter;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import com.alechilles.alecstamework.persistence.runtime.PersistenceFailureSignal;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Root /tw command dispatcher.
 */
public final class TameworkCommandRoot extends AbstractCommandCollection {
    public static final String ROOT_PERMISSION = "tamework.command.tw";

    public TameworkCommandRoot() {
        this(null, null, null, new SpawnBeaconVisualizationService(), null, null);
    }

    public TameworkCommandRoot(
            @Nullable PersistenceDiagnosticsReader persistenceDiagnostics
    ) {
        this(persistenceDiagnostics, null, null, new SpawnBeaconVisualizationService(), null, null);
    }

    public TameworkCommandRoot(
            @Nullable PersistenceDiagnosticsReader persistenceDiagnostics,
            @Nullable PersistenceDiagnosticExporter persistenceExporter
    ) {
        this(persistenceDiagnostics, persistenceExporter, null, new SpawnBeaconVisualizationService(), null, null);
    }

    public TameworkCommandRoot(
            @Nullable PersistenceDiagnosticsReader persistenceDiagnostics,
            @Nullable PersistenceDiagnosticExporter persistenceExporter,
            @Nullable BondedCompanionDiagnosticContributor bondedDiagnostics
    ) {
        this(
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics,
                new SpawnBeaconVisualizationService(),
                null,
                null
        );
    }

    public TameworkCommandRoot(
            @Nullable PersistenceDiagnosticsReader persistenceDiagnostics,
            @Nullable PersistenceDiagnosticExporter persistenceExporter,
            @Nullable BondedCompanionDiagnosticContributor bondedDiagnostics,
            @Nonnull SpawnBeaconVisualizationService spawnBeaconVisualizationService
    ) {
        this(
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics,
                spawnBeaconVisualizationService,
                null,
                null
        );
    }

    public TameworkCommandRoot(
            @Nullable PersistenceDiagnosticsReader persistenceDiagnostics,
            @Nullable PersistenceDiagnosticExporter persistenceExporter,
            @Nullable BondedCompanionDiagnosticContributor bondedDiagnostics,
            @Nonnull SpawnBeaconVisualizationService spawnBeaconVisualizationService,
            @Nullable PublicPersistenceOperations persistenceOperations
    ) {
        this(
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics,
                spawnBeaconVisualizationService,
                null,
                persistenceOperations
        );
    }

    public TameworkCommandRoot(
            @Nullable PersistenceDiagnosticsReader persistenceDiagnostics,
            @Nullable PersistenceDiagnosticExporter persistenceExporter,
            @Nullable BondedCompanionDiagnosticContributor bondedDiagnostics,
            @Nonnull SpawnBeaconVisualizationService spawnBeaconVisualizationService,
            @Nullable PublicPersistenceQueries persistenceQueries,
            @Nullable PublicPersistenceOperations persistenceOperations
    ) {
        this(
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics,
                spawnBeaconVisualizationService,
                persistenceQueries,
                persistenceOperations,
                null
        );
    }

    public TameworkCommandRoot(
            @Nullable PersistenceDiagnosticsReader persistenceDiagnostics,
            @Nullable PersistenceDiagnosticExporter persistenceExporter,
            @Nullable BondedCompanionDiagnosticContributor bondedDiagnostics,
            @Nonnull SpawnBeaconVisualizationService spawnBeaconVisualizationService,
            @Nullable PublicPersistenceQueries persistenceQueries,
            @Nullable PublicPersistenceOperations persistenceOperations,
            @Nullable Consumer<PersistenceFailureSignal> persistenceFailureSink
    ) {
        super("tw", "Tamework commands.");
        requirePermission(ROOT_PERMISSION);
        setPermissionGroups(TameworkConfigPermission.adminPermissionGroups());
        addSubCommand(new TameworkDebugCommand(
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics,
                spawnBeaconVisualizationService,
                persistenceQueries,
                persistenceOperations,
                persistenceFailureSink
        ));
        addSubCommand(new TameworkNpcCommand());
        addSubCommand(new TameworkApiCommandCollection());
        addSubCommand(new TameworkConfigCommandGroup());
        addSubCommand(new TameworkSettingsCommand());
        addSubCommand(new TameworkNewsCommand());
        addSubCommand(new TameworkRuntimeCommand());
    }
}
