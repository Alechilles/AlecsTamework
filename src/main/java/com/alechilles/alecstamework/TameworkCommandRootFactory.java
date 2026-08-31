package com.alechilles.alecstamework;

import com.alechilles.alecstamework.commands.SpawnBeaconVisualizationService;
import com.alechilles.alecstamework.commands.TameworkCommandRoot;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceDiagnosticExporter;
import com.alechilles.alecstamework.persistence.runtime.PersistenceFailureSignal;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/** Builds the runtime command root from the active persistence compositions. */
final class TameworkCommandRootFactory {
    private TameworkCommandRootFactory() {
    }

    @Nonnull
    static TameworkCommandRoot create(
            @Nullable TameworkPersistenceComposition persistence,
            @Nullable TameworkBondedCompanionComposition bonded,
            @Nonnull SpawnBeaconVisualizationService spawnBeacons,
            @Nullable Consumer<PersistenceFailureSignal> failureSink
    ) {
        return new TameworkCommandRoot(
                persistence == null ? null : persistence.diagnosticsReader(),
                persistence == null ? null : persistence.diagnosticsExporter(),
                bonded == null ? null : bonded.diagnostics(),
                spawnBeacons,
                persistence == null ? null : persistence.facades().queries(),
                persistence == null ? null : persistence.facades().operations(),
                failureSink
        );
    }

    @Nonnull
    static TameworkCommandRoot bondedOnly(
            @Nonnull PersistenceDiagnosticExporter exporter,
            @Nonnull TameworkBondedCompanionComposition bonded,
            @Nonnull SpawnBeaconVisualizationService spawnBeacons,
            @Nullable Consumer<PersistenceFailureSignal> failureSink
    ) {
        return new TameworkCommandRoot(
                null, exporter, bonded.diagnostics(), spawnBeacons,
                null, null, failureSink
        );
    }
}
