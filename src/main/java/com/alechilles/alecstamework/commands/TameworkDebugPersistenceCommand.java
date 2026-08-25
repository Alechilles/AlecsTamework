package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceDiagnosticExporter;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDiagnosticsReader;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import javax.annotation.Nullable;

/** Groups generic and bonded persistence diagnostics. */
public final class TameworkDebugPersistenceCommand extends AbstractCommandCollection {
    public TameworkDebugPersistenceCommand(
            PersistenceDiagnosticsReader persistenceDiagnostics,
            PersistenceDiagnosticExporter persistenceExporter,
            BondedCompanionDiagnosticContributor bondedDiagnostics,
            @Nullable PublicPersistenceQueries persistenceQueries,
            @Nullable PublicPersistenceOperations persistenceOperations
    ) {
        super("persistence", "Tamework persistence diagnostics.");
        addSubCommand(new TameworkDebugDbCommand(
                persistenceDiagnostics, persistenceExporter, bondedDiagnostics
        ));
        addSubCommand(new TameworkDebugReviveReadyCommand(
                persistenceQueries, persistenceOperations
        ));
    }
}
