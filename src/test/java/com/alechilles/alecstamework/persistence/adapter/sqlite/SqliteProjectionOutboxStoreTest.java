package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.projection.ProjectionCheckpoint;
import com.alechilles.alecstamework.persistence.projection.ProjectionCompactionPolicy;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction integration tests for monotonic non-compacting projection evidence. */
class SqliteProjectionOutboxStoreTest {
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("lifecycle_changed");
    private static final ProjectionConsumerId CONSUMER =
            new ProjectionConsumerId("identity_index");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
    }

    @Test
    void appendsMonotonicEventsInTheOwningCanonicalTransaction() throws Exception {
        try (Connection connection = transaction()) {
            createOperation(connection);
            SqliteProjectionOutboxStore store = new SqliteProjectionOutboxStore(connection);
            ProjectionEventDraft firstDraft = draft(1, "{\"revision\":1}", -9_000);
            ProjectionEvent first = store.append(firstDraft).value();
            ProjectionEvent second = store.append(
                    draft(2, "{\"revision\":2}", -8_000)
            ).value();

            assertEquals(1, first.sequence().value());
            assertEquals(2, second.sequence().value());
            assertEquals(second.sequence(), store.head());
            assertEquals(List.of(first, second), store.readAfter(ProjectionSequence.ORIGIN, 10));
            assertEquals(first, store.append(firstDraft).value());
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.append(draft(1, "{\"different\":true}", -9_000)).status()
            );
            assertEquals(ProjectionCompactionPolicy.DISABLED,
                    ProjectionCompactionPolicy.values()[0]);
            connection.commit();
        }
    }

    @Test
    void checkpointsAdvanceMonotonicallyAndNeverPastTheOutboxHead() throws Exception {
        try (Connection connection = transaction()) {
            createOperation(connection);
            SqliteProjectionOutboxStore store = new SqliteProjectionOutboxStore(connection);
            ProjectionEvent first = store.append(draft(1, "{}", -9_000)).value();
            ProjectionEvent second = store.append(draft(2, "{}", -8_000)).value();

            ProjectionCheckpoint secondAck =
                    store.acknowledge(CONSUMER, second.sequence(), -7_000).value();
            assertEquals(second.sequence(), secondAck.acknowledgedSequence());
            assertEquals(
                    secondAck,
                    store.acknowledge(CONSUMER, first.sequence(), -6_000).value()
            );
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.acknowledge(
                            CONSUMER,
                            new ProjectionSequence(second.sequence().value() + 1),
                            -5_000
                    ).status()
            );
            connection.commit();
        }
    }

    @Test
    void invalidEventJsonFailsExplicitlyAndRollbackRemovesTheWholeOutboxWrite()
            throws Exception {
        try (Connection connection = transaction()) {
            createOperation(connection);
            SqliteProjectionOutboxStore store = new SqliteProjectionOutboxStore(connection);
            assertThrows(PersistenceStoreException.class,
                    () -> store.append(draft(1, "not-json", -9_000)));
            assertTrue(store.append(draft(1, "{}", -9_000)).applied());
            connection.rollback();
        }
        try (Connection connection = connections.openReadConnection()) {
            SqliteProjectionOutboxStore store = new SqliteProjectionOutboxStore(connection);
            assertEquals(ProjectionSequence.ORIGIN, store.head());
            assertTrue(store.findCheckpoint(CONSUMER).isEmpty());
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private void createOperation(Connection connection) {
        new SqliteOperationStore(connection).prepare(new PreparedOperation(
                OPERATION, new IdempotencyKey("projection-test"),
                new OperationKind("projection_test"), 1, "{}", "test",
                null, List.of(), -10_000
        ));
    }

    private ProjectionEventDraft draft(long revision, String json, long createdAtMs) {
        return new ProjectionEventDraft(
                OPERATION, EVENT_TYPE, "profile-a", revision, 1, json, createdAtMs
        );
    }
}
