package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationReconciliationProgress;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Maps internal reconciliation progress without exposing runtime reconciliation types publicly. */
final class PopulationReconciliationDiagnosticsMapper {
    private PopulationReconciliationDiagnosticsMapper() {
    }

    @Nonnull
    static PopulationDiagnosticsView.ReconciliationView map(
            @Nonnull CompanionPopulationReconciliationProgress progress
    ) {
        Objects.requireNonNull(progress, "progress");
        return new PopulationDiagnosticsView.ReconciliationView(
                progress.status().name(),
                progress.reason(),
                progress.scannedUnits(),
                progress.totalUnits(),
                progress.profileCount(),
                progress.duplicateObservations(),
                progress.recoveredOperations(),
                progress.canceledOperations(),
                progress.startedAtMs(),
                progress.completedAtMs()
        );
    }
}
