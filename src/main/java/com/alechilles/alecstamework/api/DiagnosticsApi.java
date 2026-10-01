package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

public interface DiagnosticsApi {
    @Nonnull
    PersistenceDiagnosticsView getPersistenceDiagnostics();

    /** Additive population diagnostics; older implementations report an unavailable snapshot. */
    @Nonnull
    default PopulationDiagnosticsView getPopulationDiagnostics() {
        return PopulationDiagnosticsView.unavailable();
    }
}
