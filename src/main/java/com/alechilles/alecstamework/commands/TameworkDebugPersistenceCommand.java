package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceDiagnosticExporter;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDiagnosticsReader;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import com.alechilles.alecstamework.persistence.runtime.PersistenceFailureSignal;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/** Groups generic and bonded persistence diagnostics. */
public final class TameworkDebugPersistenceCommand extends AbstractCommandCollection {
    public TameworkDebugPersistenceCommand(
            PersistenceDiagnosticsReader persistenceDiagnostics,
            PersistenceDiagnosticExporter persistenceExporter,
            BondedCompanionDiagnosticContributor bondedDiagnostics,
            @Nullable PublicPersistenceQueries persistenceQueries,
            @Nullable PublicPersistenceOperations persistenceOperations
    ) {
        this(
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics,
                persistenceQueries,
                persistenceOperations,
                null
        );
    }

    public TameworkDebugPersistenceCommand(
            PersistenceDiagnosticsReader persistenceDiagnostics,
            PersistenceDiagnosticExporter persistenceExporter,
            BondedCompanionDiagnosticContributor bondedDiagnostics,
            @Nullable PublicPersistenceQueries persistenceQueries,
            @Nullable PublicPersistenceOperations persistenceOperations,
            @Nullable Consumer<PersistenceFailureSignal> failureSink
    ) {
        super("persistence", "server.tamework.commands.debugPersistence.description");
        // status, health, detail, export, reviveready and compact served the old SQLite runtime.
        // Since the index cut-over they only answered "unavailable", so they are not registered.
        // Their classes go with the old runtime.
        addSubCommand(new TameworkDebugPersistenceFailureCommand(failureSink));
    }
}
