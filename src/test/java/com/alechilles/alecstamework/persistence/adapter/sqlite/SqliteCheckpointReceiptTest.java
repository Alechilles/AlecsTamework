package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationAction;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for compacted internal checkpoint operation replays. */
class SqliteCheckpointReceiptTest {
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final IdempotencyKey IDEMPOTENCY = new IdempotencyKey(
            SqliteCheckpointReceipt.IDEMPOTENCY_PREFIX
                    + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    );
    private static final ProfileExtensionKey KEY = new ProfileExtensionKey(
            PROFILE,
            SqliteCheckpointReceipt.NAMESPACE,
            "30000000-0000-0000-0000-000000000001"
    );

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteProfileExtensionOperations operations;
    private SqliteOperationPublisher publisher;
    private ProjectionConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        try (Connection connection = transaction()) {
            new SqliteCompanionIdentityStore(connection).createProfile(new CompanionIdentity(
                    PROFILE,
                    "Companion",
                    "role",
                    null,
                    null,
                    null,
                    -10_000,
                    -10_000,
                    -10_000,
                    0
            ));
            connection.commit();
        }
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry(
                        List.of(ProfileExtensionMutationDefinition.INSTANCE)
                ),
                units
        );
        SqliteOperationEvidenceReader evidence = new SqliteOperationEvidenceReader(reads);
        ProjectionCoordinator projections = new ProjectionCoordinator(
                new SqliteProjectionGateway(reads, units),
                ProjectionRetryPolicy.DEFAULT,
                () -> -5_000
        );
        consumer = new CheckpointConsumer();
        operations = new SqliteProfileExtensionOperations(
                new SqliteDatabaseOperationCoordinator(engine, evidence, projections, () -> -5_000),
                List.of(consumer)
        );
        publisher = new SqliteOperationPublisher(engine, evidence, projections, () -> -5_000);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown(Duration.ofSeconds(5));
        }
        if (reads != null) {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void compactedCheckpointReplayDoesNotReapplyTheCanonicalMutation() throws Exception {
        ProfileExtensionMutation original = mutation("{\"value\":1}");
        OperationWorkflowResult first = submit(original);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, first.status());
        assertEquals(1, first.events().size());

        compact(first.operation());

        OperationWorkflowResult racedPublication = publisher.resume(
                first.operation(),
                List.of(consumer)
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, racedPublication.status());
        assertTrue(racedPublication.events().isEmpty());

        OperationWorkflowResult replay = submit(original);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertTrue(replay.events().isEmpty());
        assertEquals(1, storedValue().revision());
        assertEquals("{\"value\":1}", storedValue().jsonPayload());

        OperationWorkflowResult changed = submit(mutation("{\"value\":2}"));
        assertEquals(OperationWorkflowResult.Status.PREPARE_FAILED, changed.status());
        assertEquals(1, storedValue().revision());
        assertEquals("{\"value\":1}", storedValue().jsonPayload());
    }

    private OperationWorkflowResult submit(ProfileExtensionMutation mutation) throws Exception {
        return operations.submit(OPERATION, IDEMPOTENCY, mutation).completion()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private ProfileExtensionMutation mutation(String payload) {
        return new ProfileExtensionMutation(
                KEY,
                ProfileExtensionMutationAction.PUT,
                null,
                payload,
                -9_000
        );
    }

    private void compact(OperationEnvelope operation) throws Exception {
        try (Connection connection = transaction();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE operation_envelope SET payload_json = ? WHERE operation_id = ?"
             );
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM projection_outbox WHERE operation_id = ?"
             )) {
            update.setString(1, SqliteCheckpointReceipt.create(operation.payloadJson()));
            update.setString(2, operation.operationId().toString());
            assertEquals(1, update.executeUpdate());
            delete.setString(1, operation.operationId().toString());
            assertEquals(1, delete.executeUpdate());
            connection.commit();
        }
    }

    private ProfileExtensionData storedValue() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            PersistenceReadResult.Found<ProfileExtensionData> found = assertInstanceOf(
                    PersistenceReadResult.Found.class,
                    new SqliteProfileExtensionDataStore(connection).find(KEY)
            );
            return found.value();
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static final class CheckpointConsumer implements ProjectionConsumer {
        @Override
        public ProjectionConsumerId consumerId() {
            return new ProjectionConsumerId("checkpoint_receipt_test");
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event) {
            return ProjectionApplyOutcome.APPLIED;
        }
    }
}
