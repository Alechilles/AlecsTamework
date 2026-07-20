package com.alechilles.alecstamework.performance;

import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityService;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationContext;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationDelta;
import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineState;
import com.alechilles.alecstamework.persistence.incidents.PersistenceResilienceRuntime;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Numeric regression gates for indexed admission and large quarantine reconstruction. */
class PersistenceResiliencePerformanceGateTest {
    private static final int ACTIVE_SCOPE_FIXTURE_SIZE = 10_000;
    private static final int AVAILABILITY_DECISIONS = 250_000;
    private static final long AVAILABILITY_BUDGET_MS = 4_000L;
    private static final long RELOAD_BUDGET_MS = 5_000L;

    @TempDir
    Path tempDir;

    @Test
    void exactAvailabilityChecksRemainBoundedWithManyActiveScopes() {
        PersistenceQuarantineRegistry quarantines = new PersistenceQuarantineRegistry();
        for (int index = 0; index < ACTIVE_SCOPE_FIXTURE_SIZE; index++) {
            PersistenceScope scope = scope("quarantined-" + index);
            quarantines.openImmediate(new PersistenceQuarantineRecord(
                    "q-" + index, "incident-" + index, scope, PersistenceDomain.OWNER_MUTATION,
                    "publication_failed", PersistenceQuarantineState.ACTIVE,
                    "evidence-" + index, 0L, 1L, 1L, 0L, null));
        }
        PersistenceMutationAvailabilityService availability = new PersistenceMutationAvailabilityService(
                new PersistenceStorageHealthService(), quarantines,
                new PersistenceFeatureCircuitRegistry(), ignored -> true);
        PersistenceMutationContext healthy = new PersistenceMutationContext(
                PersistenceDomain.OWNER_MUTATION, "performance-probe", List.of(scope("healthy")),
                Set.of(), PersistenceMutationDelta.ZERO, null, null, false, false);

        for (int warmup = 0; warmup < 10_000; warmup++) assertTrue(availability.decide(healthy).allowed());
        long started = System.nanoTime();
        for (int probe = 0; probe < AVAILABILITY_DECISIONS; probe++) {
            assertTrue(availability.decide(healthy).allowed());
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        System.out.printf("availability decisions=%d activeScopes=%d elapsedMs=%d budgetMs=%d%n",
                AVAILABILITY_DECISIONS, ACTIVE_SCOPE_FIXTURE_SIZE, elapsedMs, AVAILABILITY_BUDGET_MS);
        assertTrue(elapsedMs <= AVAILABILITY_BUDGET_MS,
                "exact availability index exceeded budget: " + elapsedMs + "ms");
    }

    @Test
    void bootReloadOfLargeDurableQuarantineSetRemainsBounded() throws Exception {
        Path database = tempDir.resolve("large-quarantine-reload.sqlite");
        SqliteConnectionManager connections = new SqliteConnectionManager(database);
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertActiveQuarantines(connection);
        }

        PersistenceWriteQueue queue = new PersistenceWriteQueue(
                connections, new PersistenceHealthService(), null);
        PersistenceResilienceRuntime runtime = null;
        long started = System.nanoTime();
        try {
            runtime = PersistenceResilienceRuntime.initialize(
                    "performance-boot", connections, queue,
                    new PersistenceStorageHealthService(), null);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.printf("quarantine reload activeScopes=%d elapsedMs=%d budgetMs=%d%n",
                    ACTIVE_SCOPE_FIXTURE_SIZE, elapsedMs, RELOAD_BUDGET_MS);
            assertEquals(ACTIVE_SCOPE_FIXTURE_SIZE, runtime.quarantines().size());
            assertTrue(elapsedMs <= RELOAD_BUDGET_MS,
                    "durable quarantine reload exceeded budget: " + elapsedMs + "ms");
        } finally {
            if (runtime != null) runtime.close();
            queue.close();
        }
    }

    private void insertActiveQuarantines(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try (PreparedStatement incident = connection.prepareStatement("""
                INSERT INTO persistence_incidents (
                    incident_id, fingerprint, status, severity, failure_class, disposition,
                    domain, phase, reason_code, boot_id, opened_at_ms, last_seen_at_ms,
                    occurrence_count, recovery_attempts, evidence_json
                ) VALUES (?, ?, 'OPEN', 'WARNING', 'POST_COMMIT_PUBLICATION_FAILURE',
                    'SCOPED_QUARANTINE', 'OWNER_MUTATION', 'PUBLICATION',
                    'publication_failed', 'fixture-boot', 1, 1, 1, 0, '{}')
                """);
             PreparedStatement scope = connection.prepareStatement("""
                INSERT INTO persistence_incident_scopes (
                    incident_id, scope_type, scope_key, scope_hash, authority_dimension, created_at_ms
                ) VALUES (?, 'PROFILE', ?, ?, 'canonical_profile_catalog', 1)
                """);
             PreparedStatement quarantine = connection.prepareStatement("""
                INSERT INTO persistence_quarantines (
                    quarantine_id, incident_id, scope_type, scope_key, domain, reason_code,
                    state, evidence_hash, generation, created_at_ms, updated_at_ms
                ) VALUES (?, ?, 'PROFILE', ?, 'OWNER_MUTATION', 'publication_failed',
                    'ACTIVE', ?, 0, 1, 1)
                """)) {
            for (int index = 0; index < ACTIVE_SCOPE_FIXTURE_SIZE; index++) {
                String incidentId = "incident-" + index;
                String scopeKey = "profile-" + index;
                incident.setString(1, incidentId);
                incident.setString(2, "fingerprint-" + index);
                incident.addBatch();
                scope.setString(1, incidentId);
                scope.setString(2, scopeKey);
                scope.setString(3, "scope-hash-" + index);
                scope.addBatch();
                quarantine.setString(1, "quarantine-" + index);
                quarantine.setString(2, incidentId);
                quarantine.setString(3, scopeKey);
                quarantine.setString(4, "evidence-" + index);
                quarantine.addBatch();
            }
            incident.executeBatch();
            scope.executeBatch();
            quarantine.executeBatch();
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private PersistenceScope scope(String key) {
        return new PersistenceScope(
                PersistenceScopeType.PROFILE, key, "safe-hash-" + key,
                "canonical_profile_catalog");
    }
}
