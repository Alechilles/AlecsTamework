package com.alechilles.alecstamework.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PopulationDiagnosticsViewCompatibilityTest {
    @Test
    void originalConstructorsRemainAvailableWithSafeExtensionDefaults() {
        PopulationDiagnosticsView.LookupMetricsView lookups =
                new PopulationDiagnosticsView.LookupMetricsView(
                        1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, null
                );
        PopulationDiagnosticsView view = new PopulationDiagnosticsView(
                new PopulationDiagnosticsView.ReadinessView("READY", "READY", "READY"),
                new PopulationDiagnosticsView.CountView(0L, 0L, 0L, 0L, 0L, 0L, 0L),
                new PopulationDiagnosticsView.ReservationMetricsView(0L, 0L, 0L, 0L, 0L),
                new PopulationDiagnosticsView.ReservationMetricsView(0L, 0L, 0L, 0L, 0L),
                lookups,
                PopulationDiagnosticsView.ReconciliationView.unknown()
        );

        assertEquals(0L, lookups.targetedRefreshCount());
        assertEquals("UNKNOWN", view.activeRules().operation());
        assertEquals(-1, view.activeRules().ownerLimit());
    }
}
