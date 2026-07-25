package com.alechilles.alecstamework.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsApiCompatibilityTest {
    @Test
    void legacyImplementationGetsConservativeResilienceDefaults() {
        DiagnosticsApi legacy = () -> new PersistenceDiagnosticsView(
                "database", 0L, 0L, 0L, 0L,
                new PersistenceDiagnosticsView.QueueMetricsView(
                        0, 0, 0, 0L, 0L, 0L, 0L,
                        0.0, 0.0, 0.0, null, 0L),
                new PersistenceDiagnosticsView.HealthView("HEALTHY", null, 0L));

        assertEquals("READ_ONLY", legacy.getPersistenceResilience().storageState());
        assertEquals("GLOBAL_READ_ONLY", legacy.queryPersistenceAvailability(
                new PersistenceMutationAvailabilityRequest(
                        PersistenceMutationDomain.OWNER_MUTATION, "compatibility-check",
                        java.util.List.of(), java.util.Set.of(),
                        PersistenceMutationDirection.ZERO, null, null, false, false)).status());
        assertTrue(legacy.findPersistenceIncident("deadbeef").isEmpty());
    }
}
