package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.incidents.IncidentRecord;
import com.alechilles.alecstamework.persistence.incidents.IncidentState;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction integration tests for replacement incident containment. */
class SqliteIncidentStoreTest {
    private static final IncidentId INCIDENT =
            new IncidentId(UUID.fromString("50000000-0000-0000-0000-000000000001"));
    private static final IncidentId OTHER_INCIDENT =
            new IncidentId(UUID.fromString("50000000-0000-0000-0000-000000000002"));
    private static final OperationScope PROFILE_SCOPE = OperationScope.profile(
            ProfileId.parse("20000000-0000-0000-0000-000000000001")
    );

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
    }

    @Test
    void createsResolvesAndIdempotentlyReadsIncidentEvidence() throws Exception {
        try (Connection connection = transaction()) {
            SqliteIncidentStore store = new SqliteIncidentStore(connection);
            IncidentRecord incident = incident(INCIDENT, "{}");
            assertTrue(store.createIncident(incident).applied());
            assertTrue(store.createIncident(incident).applied());
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.createIncident(new IncidentRecord(
                            INCIDENT, "UNKNOWN", "different", IncidentState.OPEN,
                            "different", "{}", -9_000, null
                    )).status()
            );

            IncidentRecord resolved = store.resolveIncident(INCIDENT, -8_000).value();
            assertEquals(IncidentState.RESOLVED, resolved.state());
            assertEquals(-8_000, resolved.resolvedAtMs());
            assertEquals(resolved, store.resolveIncident(INCIDENT, -7_000).value());
            connection.commit();
        }
    }

    @Test
    void scopesQuarantineToExactEvidenceAndFencesRelease() throws Exception {
        try (Connection connection = transaction()) {
            SqliteIncidentStore store = new SqliteIncidentStore(connection);
            store.createIncident(incident(INCIDENT, "{}"));
            ScopeQuarantine quarantine = new ScopeQuarantine(
                    PROFILE_SCOPE, INCIDENT, QuarantineState.ACTIVE,
                    "unknown_commit", -9_000, null
            );
            assertTrue(store.quarantine(quarantine).applied());
            assertEquals(
                    List.of(quarantine),
                    store.findActiveQuarantines(List.of(
                            OperationScope.global(), PROFILE_SCOPE, PROFILE_SCOPE
                    ))
            );
            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    store.release(PROFILE_SCOPE, OTHER_INCIDENT, -8_000).status()
            );

            ScopeQuarantine released =
                    store.release(PROFILE_SCOPE, INCIDENT, -8_000).value();
            assertEquals(QuarantineState.RELEASED, released.state());
            assertTrue(store.findActiveQuarantines(List.of(PROFILE_SCOPE)).isEmpty());
            connection.commit();
        }
    }

    @Test
    void refusesQuarantineWithoutAnOpenIncidentAndNeverTreatsBadJsonAsAbsence()
            throws Exception {
        try (Connection connection = transaction()) {
            SqliteIncidentStore store = new SqliteIncidentStore(connection);
            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    store.quarantine(new ScopeQuarantine(
                            PROFILE_SCOPE, INCIDENT, QuarantineState.ACTIVE,
                            "missing_incident", -9_000, null
                    )).status()
            );
            assertThrows(PersistenceStoreException.class,
                    () -> store.createIncident(incident(INCIDENT, "not-json")));
            connection.rollback();
        }
    }

    @Test
    void storeNeverCommitsBehindItsOwningTransaction() throws Exception {
        try (Connection connection = transaction()) {
            assertTrue(new SqliteIncidentStore(connection)
                    .createIncident(incident(INCIDENT, "{}")).applied());
            connection.rollback();
        }
        try (Connection connection = connections.openReadConnection()) {
            assertFalse(new SqliteIncidentStore(connection).findIncident(INCIDENT).isPresent());
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private IncidentRecord incident(IncidentId incidentId, String evidence) {
        return new IncidentRecord(
                incidentId, "UNKNOWN", "unknown_commit", IncidentState.OPEN,
                "Operation outcome needs exact recovery", evidence, -10_000, null
        );
    }
}
