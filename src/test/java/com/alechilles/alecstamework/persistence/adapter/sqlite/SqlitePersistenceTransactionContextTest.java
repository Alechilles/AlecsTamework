package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves all shared stores compose on one caller-owned application transaction. */
class SqlitePersistenceTransactionContextTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
    }

    @Test
    void rollbackAtomicallyRemovesOperationProfileLifecycleAndOutbox() throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.operations().prepare(new PreparedOperation(
                    OPERATION, new IdempotencyKey("atomic-test"),
                    new OperationKind("atomic_test"), 1, "{}", "test",
                    null, List.of(), -10_000
            ));
            transaction.identities().createProfile(new CompanionIdentity(
                    PROFILE, "Companion", "role", null, null, "world",
                    -10_000, -10_000, -10_000, 0
            ));
            transaction.lifecycles().create(new CompanionLifecycle(
                    PROFILE, null, LifecycleState.UNRESOLVED, LifecycleLocation.unresolved(),
                    LifecycleRevision.INITIAL, null, -10_000,
                    ReconciliationGeneration.INITIAL, null
            ));
            transaction.outbox().append(new ProjectionEventDraft(
                    OPERATION, new ProjectionEventType("profile_created"),
                    PROFILE.toString(), 0, 1, "{}", -10_000
            ));
            connection.rollback();
        }

        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertTrue(transaction.operations().find(OPERATION).isEmpty());
            assertTrue(transaction.identities().findProfile(PROFILE).isEmpty());
            assertTrue(transaction.lifecycles().findByProfile(PROFILE).isEmpty());
            assertEquals(ProjectionSequence.ORIGIN, transaction.outbox().head());
        }
    }
}
