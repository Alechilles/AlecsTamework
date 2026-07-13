package com.alechilles.alecstamework.persistence.sqlite;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for retaining the actionable cause of persistence quarantine. */
class PersistenceHealthServiceTest {
    @Test
    void laterRejectionsDoNotOverwriteTheInitiatingFailure() {
        List<PersistenceHealthService.HealthState> transitions = new ArrayList<>();
        PersistenceHealthService health = new PersistenceHealthService(transitions::add);

        assertTrue(health.markDegraded("coop_release_identity_durable_mark_failed"));
        PersistenceHealthService.HealthState first = health.getState();
        assertFalse(health.markDegraded("population-observation-failed:persistence_unhealthy"));

        assertEquals("coop_release_identity_durable_mark_failed", health.getState().reason());
        assertEquals(first.lastFailureAtMs(), health.getState().lastFailureAtMs());
        assertEquals(List.of(first), transitions);
    }

    @Test
    void explicitHealthyTransitionAllowsANewFailureToBecomeTheCause() {
        PersistenceHealthService health = new PersistenceHealthService();
        health.markDegraded("first");

        health.markHealthy();

        assertTrue(health.markDegraded("second"));
        assertEquals("second", health.getState().reason());
    }
}
