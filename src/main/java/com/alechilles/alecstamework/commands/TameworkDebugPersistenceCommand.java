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
        super("persistence", "Tamework persistence diagnostics.");
        addSubCommand(diagnostic(
                TameworkDebugDbCommand.Action.STATUS,
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics
        ));
        addSubCommand(diagnostic(
                TameworkDebugDbCommand.Action.HEALTH,
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics
        ));
        addSubCommand(diagnostic(
                TameworkDebugDbCommand.Action.DETAIL,
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics
        ));
        addSubCommand(diagnostic(
                TameworkDebugDbCommand.Action.EXPORT,
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics
        ));
        addSubCommand(new TameworkDebugReviveReadyCommand(
                persistenceQueries, persistenceOperations
        ));
        addSubCommand(new TameworkDebugPersistenceFailureCommand(failureSink));
    }

    private TameworkDebugDbCommand diagnostic(
            TameworkDebugDbCommand.Action action,
            PersistenceDiagnosticsReader persistenceDiagnostics,
            PersistenceDiagnosticExporter persistenceExporter,
            BondedCompanionDiagnosticContributor bondedDiagnostics
    ) {
        return new TameworkDebugDbCommand(
                action,
                persistenceDiagnostics,
                persistenceExporter,
                bondedDiagnostics
        );
    }
}
