package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationAction;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationEventCodec;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationOutcome;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionProjectionIndex;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionProjectionValue;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the 4.0 checkpoint-operation history growth report. */
class SqliteCheckpointHistoryStoreTest {
    private static final long NOW = 10_000_000L;
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileExtensionKey KEY = new ProfileExtensionKey(
            PROFILE, SqliteCheckpointReceipt.NAMESPACE, "alias:checkpoint"
    );

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV2Manager(connections, () -> NOW).initialize();
        try (Connection connection = transaction()) {
            new SqliteCompanionIdentityStore(connection).createProfile(
                    new CompanionIdentity(
                            PROFILE, "Companion", "role", null, null, null,
                            NOW - 10_000L, NOW - 10_000L, NOW - 10_000L, 0
                    )
            );
            connection.commit();
        }
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
    void publicationCadenceCompactsOldSupersededCheckpointsWithoutChangingProjection()
            throws Exception {
        long oldPublishedAt = NOW - SqliteCheckpointHistoryStore.RETENTION_MS - 1;
        int interval = SqliteCheckpointHistoryMaintenance.PUBLICATION_INTERVAL;
        List<OperationEnvelope> old = new ArrayList<>();
        List<OperationEnvelope> recent = new ArrayList<>();
        try (Connection connection = transaction()) {
            seedCanonical(connection, interval * 2L + 1,
                    "{\"checkpoint\":" + (interval * 2L + 1) + "}");
            for (int index = 1; index <= interval; index++) {
                OperationEnvelope operation = checkpoint(
                        index, index, oldPublishedAt
                );
                old.add(operation);
                seedOperationAndEvent(connection, operation, index);
            }
            for (int index = interval + 1; index <= interval * 2; index++) {
                OperationEnvelope operation = checkpoint(index, index, NOW);
                recent.add(operation);
                seedOperationAndEvent(connection, operation, index);
            }
            seedOperationAndEvent(connection, ordinary(interval * 2 + 1, NOW),
                    interval * 2L + 1);
            acknowledge(connection, interval * 2L + 1);
            connection.commit();
        }

        Map<String, ProfileExtensionProjectionValue> before = replayProjection();
        assertEquals(before, projection());
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteCheckpointHistoryMaintenance maintenance =
                new SqliteCheckpointHistoryMaintenance(
                        new SqliteUnitOfWorkRunner(writer, reads)
                );

        for (OperationEnvelope operation : old) {
            maintenance.published(operation);
        }
        awaitWriterDrain();
        for (OperationEnvelope operation : recent) {
            maintenance.published(operation);
        }
        awaitOutboxRows(interval + 1L);

        assertEquals(before, projection());
        for (OperationEnvelope operation : old) {
            assertFalse(outboxContains(operation.operationId().toString()));
            assertTrue(operationPayload(operation.operationId().toString())
                    .contains("checkpoint-operation-receipt"));
        }
        for (OperationEnvelope operation : recent) {
            assertTrue(outboxContains(operation.operationId().toString()));
        }
        assertTrue(outboxContains(ordinary(interval * 2 + 1, NOW)
                .operationId().toString()));
    }

    @Test
    void retainsRecentUnacknowledgedAndNonPublishedOrNoninternalHistory()
            throws Exception {
        String eligible;
        List<String> retained = new ArrayList<>();
        try (Connection connection = transaction()) {
            seedCanonical(connection, 2, "{\"checkpoint\":\"latest\"}");
            OperationEnvelope old = checkpoint(
                    1, 1, NOW - SqliteCheckpointHistoryStore.RETENTION_MS - 1
            );
            eligible = old.operationId().toString();
            seedOperationAndEvent(connection, old, 1);

            OperationEnvelope recent = checkpoint(
                    2, 1, NOW - SqliteCheckpointHistoryStore.RETENTION_MS + 1
            );
            retained.add(recent.operationId().toString());
            seedOperationAndEvent(connection, recent, 1);

            OperationEnvelope durable = operation(
                    3, "DURABLE", SqliteCheckpointReceipt.IDEMPOTENCY_PREFIX + "durable",
                    oldPublishedPayload(1), NOW - SqliteCheckpointHistoryStore.RETENTION_MS - 1
            );
            retained.add(durable.operationId().toString());
            seedOperationAndEvent(connection, durable, 1);

            OperationEnvelope unknown = operation(
                    4, "UNKNOWN", SqliteCheckpointReceipt.IDEMPOTENCY_PREFIX + "unknown",
                    oldPublishedPayload(1), NOW - SqliteCheckpointHistoryStore.RETENTION_MS - 1
            );
            retained.add(unknown.operationId().toString());
            seedOperationAndEvent(connection, unknown, 1);

            OperationEnvelope external = operation(
                    5, "PUBLISHED", "external-extension-update",
                    oldPublishedPayload(1), NOW - SqliteCheckpointHistoryStore.RETENTION_MS - 1
            );
            retained.add(external.operationId().toString());
            seedOperationAndEvent(connection, external, 1);

            OperationEnvelope unacknowledged = checkpoint(
                    6, 1, NOW - SqliteCheckpointHistoryStore.RETENTION_MS - 1
            );
            retained.add(unacknowledged.operationId().toString());
            seedOperationAndEvent(connection, unacknowledged, 1);

            OperationEnvelope latest = ordinary(7, NOW);
            retained.add(latest.operationId().toString());
            seedOperationAndEvent(connection, latest, 1);
            acknowledge(connection, 5);

            SqliteCheckpointHistoryStore store =
                    new SqliteCheckpointHistoryStore(connection);
            SqliteCheckpointHistoryStore.Batch batch = store.select(0, NOW);
            assertEquals(List.of(eligible), batch.receipts().stream()
                    .map(SqliteCheckpointHistoryStore.Receipt::operationId).toList());
            store.compact(batch);
            connection.commit();
            assertTrue(store.matches(batch));
        }

        assertFalse(outboxContains(eligible));
        for (String operationId : retained) {
            assertTrue(outboxContains(operationId));
            assertFalse(operationPayload(operationId)
                    .contains("checkpoint-operation-receipt"));
        }
    }

    @Test
    void rollbackKeepsPayloadAndEventUntilExactCompactionCommit()
            throws Exception {
        try (Connection connection = transaction()) {
            seedCanonical(connection, 3, "{\"checkpoint\":\"latest\"}");
            seedOperationAndEvent(connection, checkpoint(
                    1, 1, NOW - SqliteCheckpointHistoryStore.RETENTION_MS - 1
            ), 1);
            seedOperationAndEvent(connection, checkpoint(
                    2, 2, NOW - SqliteCheckpointHistoryStore.RETENTION_MS - 1
            ), 2);
            seedOperationAndEvent(connection, ordinary(3, NOW), 3);
            acknowledge(connection, 3);
            connection.commit();

            SqliteCheckpointHistoryStore store =
                    new SqliteCheckpointHistoryStore(connection);
            SqliteCheckpointHistoryStore.Batch batch = store.select(0, NOW);
            assertEquals(2, batch.receipts().size());
            store.compact(batch);
            assertTrue(store.matches(batch));
            connection.rollback();

            assertFalse(store.matches(batch));
            assertEquals(3, outboxRows(connection));
            SqliteCheckpointHistoryStore.Batch retry = store.select(0, NOW);
            assertEquals(batch.receipts(), retry.receipts());
            store.compact(retry);
            connection.commit();
            assertTrue(store.matches(retry));
        }
    }

    private void seedCanonical(Connection connection, long revision, String json)
            throws Exception {
        ProfileExtensionData initial = ProfileExtensionData.initial(
                KEY, json, NOW - 10_000L
        );
        new SqliteProfileExtensionDataStore(connection).put(initial, 0);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE profile_extension_data
                SET revision = ?, updated_at_ms = ?
                WHERE profile_id = ? AND namespace = ? AND data_key = ?
                """)) {
            statement.setLong(1, revision);
            statement.setLong(2, NOW);
            statement.setString(3, PROFILE.toString());
            statement.setString(4, KEY.namespace());
            statement.setString(5, KEY.dataKey());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void seedOperationAndEvent(
            Connection connection, OperationEnvelope operation, long revision
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO operation_envelope(
                    operation_id, idempotency_key, operation_kind, payload_version,
                    payload_json, phase, feature_scope, created_at_ms, updated_at_ms,
                    durable_at_ms, published_at_ms, terminal_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operation.operationId().toString());
            statement.setString(2, operation.idempotencyKey().toString());
            statement.setString(3, operation.kind().toString());
            statement.setInt(4, operation.payloadVersion());
            statement.setString(5, operation.payloadJson());
            statement.setString(6, operation.phase().name());
            statement.setString(7, operation.featureScope());
            statement.setLong(8, operation.createdAtMs());
            statement.setLong(9, operation.updatedAtMs());
            nullableLong(statement, 10, operation.durableAtMs());
            nullableLong(statement, 11, operation.publishedAtMs());
            nullableLong(statement, 12, operation.terminalAtMs());
            assertEquals(1, statement.executeUpdate());
        }
        var outcome = new ProfileExtensionMutationOutcome(
                ProfileExtensionMutationOutcome.Status.APPLIED, KEY, revision,
                "{\"checkpoint\":" + revision + "}", operation.updatedAtMs());
        assertTrue(new SqliteProjectionOutboxStore(connection).append(new ProjectionEventDraft(
                operation.operationId(), ProfileExtensionMutationEventCodec.EVENT_TYPE,
                KEY.aggregateId(), revision, ProfileExtensionMutationEventCodec.VERSION,
                ProfileExtensionMutationEventCodec.encode(outcome), operation.updatedAtMs()
        )).applied());
    }

    private OperationEnvelope checkpoint(int id, long revision, long publishedAtMs) {
        return operation(
                id,
                "PUBLISHED",
                SqliteCheckpointReceipt.IDEMPOTENCY_PREFIX + id,
                oldPublishedPayload(revision),
                publishedAtMs
        );
    }

    private OperationEnvelope ordinary(int id, long publishedAtMs) {
        return operation(
                id,
                "PUBLISHED",
                "ordinary-operation-" + id,
                oldPublishedPayload(id),
                publishedAtMs
        );
    }

    private OperationEnvelope operation(
            int id, String phase, String idempotencyKey, String payload,
            long publishedAtMs
    ) {
        OperationId operationId = OperationId.parse(String.format(
                "30000000-0000-0000-0000-%012d", id
        ));
        OperationPhase operationPhase = OperationPhase.valueOf(phase);
        Long durableAtMs = operationPhase == OperationPhase.PUBLISHED
                || operationPhase == OperationPhase.DURABLE ? publishedAtMs : null;
        Long terminalAtMs = operationPhase == OperationPhase.PUBLISHED
                ? publishedAtMs : null;
        return new OperationEnvelope(
                operationId,
                new IdempotencyKey(idempotencyKey),
                ProfileExtensionMutationDefinition.KIND,
                1,
                payload,
                operationPhase,
                "profile_extension",
                null,
                null,
                0,
                0,
                operationPhase == OperationPhase.UNKNOWN ? "UNKNOWN" : null,
                operationPhase == OperationPhase.UNKNOWN ? "test" : null,
                publishedAtMs,
                publishedAtMs,
                durableAtMs,
                operationPhase == OperationPhase.PUBLISHED ? publishedAtMs : null,
                terminalAtMs,
                List.of(OperationScope.operation(operationId))
        );
    }

    private String oldPublishedPayload(long revision) {
        return ProfileExtensionMutationDefinition.INSTANCE.encode(
                new ProfileExtensionMutation(
                        KEY,
                        ProfileExtensionMutationAction.PUT,
                        null,
                        "{\"checkpoint\":" + revision + "}",
                        NOW - SqliteCheckpointHistoryStore.RETENTION_MS - 1
                )
        );
    }

    private void acknowledge(Connection connection, long sequence) {
        new SqliteProjectionOutboxStore(connection).acknowledge(
                ProfileExtensionProjectionIndex.CONSUMER_ID,
                new ProjectionSequence(sequence),
                NOW
        );
    }

    private Map<String, ProfileExtensionProjectionValue> projection()
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            PersistenceReadResult<List<ProfileExtensionData>> result =
                    new SqliteProfileExtensionDataStore(connection).findAll();
            if (!(result instanceof PersistenceReadResult.Found<
                    List<ProfileExtensionData>> found)) {
                throw new AssertionError("Expected canonical extension rows");
            }
            ProfileExtensionProjectionIndex index =
                    new ProfileExtensionProjectionIndex();
            index.rebuild(found.value());
            return index.namespace(PROFILE, KEY.namespace());
        }
    }

    private Map<String, ProfileExtensionProjectionValue> replayProjection() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            ProfileExtensionProjectionIndex index = new ProfileExtensionProjectionIndex();
            for (var event : new SqliteProjectionOutboxStore(connection)
                    .readAfter(new ProjectionSequence(0), 10_000)) {
                index.apply(event);
            }
            return index.namespace(PROFILE, KEY.namespace());
        }
    }

    private void awaitOutboxRows(long expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = connections.openReadConnection()) {
                if (outboxRows(connection) == expected) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        try (Connection connection = connections.openReadConnection()) {
            assertEquals(expected, outboxRows(connection));
        }
    }

    private void awaitWriterDrain() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (writer.outstandingOperations() != 0
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, writer.outstandingOperations());
    }

    private boolean outboxContains(String operationId) throws Exception {
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1 FROM projection_outbox WHERE operation_id = ?
                     """)) {
            statement.setString(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private String operationPayload(String operationId) throws Exception {
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT payload_json FROM operation_envelope WHERE operation_id = ?
                     """)) {
            statement.setString(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private long outboxRows(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT COUNT(*) FROM projection_outbox"
             )) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private void nullableLong(PreparedStatement statement, int index, Long value)
            throws Exception {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
