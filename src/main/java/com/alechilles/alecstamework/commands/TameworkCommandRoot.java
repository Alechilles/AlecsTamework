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
import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.item.CaptureItemFlows;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
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
        this(persistenceDiagnostics, persistenceExporter, bondedDiagnostics, spawnBeaconVisualizationService,
                persistenceQueries, persistenceOperations, persistenceFailureSink, null, null);
    }

    /** {@code releaseFlow} and {@code companions} back clear-owned; null when the companion index is not ready. */
    public TameworkCommandRoot(
            @Nullable PersistenceDiagnosticsReader persistenceDiagnostics,
            @Nullable PersistenceDiagnosticExporter persistenceExporter,
            @Nullable BondedCompanionDiagnosticContributor bondedDiagnostics,
            @Nonnull SpawnBeaconVisualizationService spawnBeaconVisualizationService,
            @Nullable PublicPersistenceQueries persistenceQueries,
            @Nullable PublicPersistenceOperations persistenceOperations,
            @Nullable Consumer<PersistenceFailureSignal> persistenceFailureSink,
            @Nullable ReleaseFlow releaseFlow,
            @Nullable CompanionQueries companions
    ) {
        super("tw", "server.tamework.commands.commandRoot.description");
        requirePermission(ROOT_PERMISSION);
        setPermissionGroups(TameworkConfigPermission.adminPermissionGroups());
        addSubCommand(new TameworkDebugCommand(
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics,
                spawnBeaconVisualizationService,
                persistenceQueries,
                persistenceOperations,
                persistenceFailureSink,
                releaseFlow,
                companions
        ));
        addSubCommand(new TameworkNpcCommand());
        addSubCommand(new TameworkApiCommandCollection());
        addSubCommand(new TameworkConfigCommandGroup());
        addSubCommand(new TameworkSettingsCommand());
        addSubCommand(new TameworkNewsCommand());
        addSubCommand(new TameworkRuntimeCommand());
    }

    /**
     * Adds {@code /tw companions forget|restore} (spec 8.14). Called once before registration;
     * {@code flows} and {@code companions} are null when the companion index is not ready.
     */
    public void addCompanionCommands(@Nullable CaptureItemFlows flows, @Nullable CompanionQueries companions) {
        addSubCommand(new TameworkCompanionsCommandGroup(flows, companions));
    }

    /**
     * Adds {@code /tw bonded grant}. Called once before registration; {@code api} gives null
     * while bonded persistence is not running.
     */
    public void addBondedCommands(
            @Nonnull java.util.function.Supplier<com.alechilles.alecstamework.companion.bonded.IndexBondedCompanionApi> api,
            @Nonnull java.util.function.Supplier<com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry.Snapshot> rosters) {
        addSubCommand(new TameworkBondedCommandGroup(api, rosters));
    }
}
