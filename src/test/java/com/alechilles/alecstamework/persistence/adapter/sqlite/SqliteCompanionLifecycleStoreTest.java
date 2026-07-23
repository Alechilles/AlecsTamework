package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.operation.OperationGeneration;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction integration tests for the sole replacement lifecycle revision path. */
class SqliteCompanionLifecycleStoreTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final OperationId WRONG_OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
    }

    @Test
    void createsAndQueriesOneCanonicalLifecycle() throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection);
            SqliteCompanionLifecycleStore store = new SqliteCompanionLifecycleStore(connection);
            CompanionLifecycle initial = initial();

            assertTrue(store.create(initial).applied());
            assertTrue(store.create(initial).applied());
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.create(new CompanionLifecycle(
                            PROFILE, OWNER, LifecycleState.UNLOADED, LifecycleLocation.none(),
                            LifecycleRevision.INITIAL, null, -9_000,
                            OperationGeneration.INITIAL, null
                    )).status()
            );
            assertEquals(initial, store.findByProfile(PROFILE).orElseThrow());
            assertEquals(java.util.List.of(initial), store.findByOwner(OWNER));
            assertEquals(java.util.List.of(initial), store.findByLocation(
                    LifecycleLocation.unresolved()
            ));
            connection.commit();
        }
    }

    @Test
    void operationEvidenceFencesAndRevisionsEveryTransition() throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection);
            SqliteCompanionLifecycleStore store = new SqliteCompanionLifecycleStore(connection);
            store.create(initial());
            insertOperation(connection, OPERATION, 0, true);
            insertOperation(connection, WRONG_OPERATION, 1, true);

            CompanionLifecycle active = lifecycle(
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity("entity-a", "world-a"),
                    1,
                    OPERATION,
                    null
            );
            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    store.transition(new LifecycleTransition(
                            LifecycleRevision.INITIAL, null,
                            lifecycle(
                                    LifecycleState.ACTIVE,
                                    LifecycleLocation.liveEntity("entity-b", "world-a"),
                                    1,
                                    WRONG_OPERATION,
                                    null
                            )
                    )).status()
            );
            assertEquals(
                    active,
                    store.transition(new LifecycleTransition(
                            LifecycleRevision.INITIAL, null, active
                    )).value()
            );

            CompanionLifecycle unloaded = lifecycle(
                    LifecycleState.UNLOADED, LifecycleLocation.none(), 2, null, null
            );
            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    store.transition(new LifecycleTransition(
                            new LifecycleRevision(1), WRONG_OPERATION, unloaded
                    )).status()
            );
            assertEquals(
                    unloaded,
                    store.transition(new LifecycleTransition(
                            new LifecycleRevision(1), OPERATION, unloaded
                    )).value()
            );
            assertEquals(
                    PersistenceMutationStatus.REVISION_MISMATCH,
                    store.transition(new LifecycleTransition(
                            new LifecycleRevision(1), OPERATION,
                            lifecycle(LifecycleState.UNLOADED, LifecycleLocation.none(), 2, null, null)
                    )).status()
            );
            connection.commit();
        }
    }

    @Test
    void quarantineAssociationAlsoUsesTheCanonicalRevisionPath() throws Exception {
        IncidentId incidentId = new IncidentId(
                UUID.fromString("50000000-0000-0000-0000-000000000001")
        );
        try (Connection connection = transaction()) {
            createProfile(connection);
            insertIncident(connection, incidentId);
            SqliteCompanionLifecycleStore store = new SqliteCompanionLifecycleStore(connection);
            store.create(initial());
            CompanionLifecycle quarantined = lifecycle(
                    LifecycleState.UNRESOLVED,
                    LifecycleLocation.unresolved(),
                    1,
                    null,
                    incidentId
            );

            assertTrue(store.transition(new LifecycleTransition(
                    LifecycleRevision.INITIAL, null, quarantined
            )).applied());
            assertTrue(store.findByProfile(PROFILE).orElseThrow().quarantined());
            connection.commit();
        }
    }

    @Test
    void neverCommitsBehindTheOwningTransaction() throws Exception {
        try (Connection connection = transaction()) {
            createProfile(connection);
            assertTrue(new SqliteCompanionLifecycleStore(connection).create(initial()).applied());
            connection.rollback();
        }
        try (Connection connection = connections.openReadConnection()) {
            assertTrue(new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE).isEmpty());
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private void createProfile(Connection connection) {
        new SqliteCompanionIdentityStore(connection).createProfile(new CompanionIdentity(
                PROFILE, "Companion", "role", null, null, "world-a",
                -10_000, -10_000, -10_000, 0
        ));
    }

    private CompanionLifecycle initial() {
        return new CompanionLifecycle(
                PROFILE, OWNER, LifecycleState.UNRESOLVED, LifecycleLocation.unresolved(),
                LifecycleRevision.INITIAL, null, -10_000,
                OperationGeneration.INITIAL, null
        );
    }

    private CompanionLifecycle lifecycle(LifecycleState state,
                                         LifecycleLocation location,
                                         long revision,
                                         OperationId operationId,
                                         IncidentId incidentId) {
        return new CompanionLifecycle(
                PROFILE, OWNER, state, location, new LifecycleRevision(revision),
                operationId, -9_000 + revision,
                OperationGeneration.INITIAL, incidentId
        );
    }

    private void insertOperation(Connection connection,
                                 OperationId operationId,
                                 long expectedRevision,
                                 boolean includeParticipant) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO operation_envelope(
                    operation_id, idempotency_key, operation_kind, payload_version,
                    payload_json, phase, feature_scope, expected_lifecycle_revision,
                    lease_owner, lease_until_ms, attempt_count, failure_kind, failure_code,
                    created_at_ms, updated_at_ms, durable_at_ms, published_at_ms, terminal_at_ms
                ) VALUES (?, ?, 'lifecycle_test', 1, '{}', 'PREPARED', 'test',
                          ?, NULL, 0, 0, NULL, NULL, -10000, -10000, NULL, NULL, NULL)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, operationId.toString());
            statement.setLong(3, expectedRevision);
            statement.executeUpdate();
        }
        if (includeParticipant) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO operation_participant(operation_id, scope_type, scope_key)
                    VALUES (?, 'PROFILE', ?)
                    """)) {
                statement.setString(1, operationId.toString());
                statement.setString(2, PROFILE.toString());
                statement.executeUpdate();
            }
        }
    }

    private void insertIncident(Connection connection, IncidentId incidentId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_incident(
                    incident_id, failure_kind, failure_code, state, summary,
                    evidence_json, created_at_ms, resolved_at_ms
                ) VALUES (?, 'INTEGRITY', 'test', 'OPEN', 'test', '{}', -10000, NULL)
                """)) {
            statement.setString(1, incidentId.toString());
            statement.executeUpdate();
        }
    }
}
