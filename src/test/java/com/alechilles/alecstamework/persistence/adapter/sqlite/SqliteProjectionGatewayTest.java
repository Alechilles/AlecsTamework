package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.projection.ProjectionBatch;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import com.alechilles.alecstamework.persistence.runtime.PersistenceThroughputMetrics;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Regression coverage for projection batch reads during concurrent publication. */
class SqliteProjectionGatewayTest {
    private static final ProjectionConsumerId CONSUMER =
            new ProjectionConsumerId("concurrent_projection_test");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
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
    void concurrentAppendCannotEscapeTheBatchHeadSnapshot() throws Exception {
        ProjectionEvent first = append(1);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(
                writer, reads
        );
        SqliteProjectionGateway gateway = new SqliteProjectionGateway(
                reads,
                units,
                () -> appendUnchecked(2)
        );

        PersistenceReadResult<ProjectionBatch> result = gateway.load(
                CONSUMER, 10
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        PersistenceReadResult.Found<ProjectionBatch> found = assertInstanceOf(
                PersistenceReadResult.Found.class, result
        );
        assertEquals(first.sequence(), found.value().head());
        assertEquals(List.of(first), found.value().events());
    }

    @Test
    void routedMetricsIncludeTrailingBypassedSequencePositions()
            throws Exception {
        ProjectionEvent relevant = append(1, "profile_changed");
        ProjectionEvent trailing = append(2, "lifecycle_changed");
        AtomicLong sequencePositions = new AtomicLong();
        AtomicInteger relevantRows = new AtomicInteger();
        SqliteProjectionGateway gateway = new SqliteProjectionGateway(
                reads,
                new SqliteUnitOfWorkRunner(writer, reads),
                new PersistenceThroughputMetrics() {
                    @Override
                    public void projectionBatchLoaded(
                            long positions,
                            int rows
                    ) {
                        sequencePositions.set(positions);
                        relevantRows.set(rows);
                    }
                }
        );

        PersistenceReadResult<ProjectionBatch> result = gateway.load(
                CONSUMER,
                ProjectionSubscription.events(Set.of(
                        new ProjectionEventType("profile_changed")
                )),
                trailing.sequence(),
                10
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        PersistenceReadResult.Found<ProjectionBatch> found = assertInstanceOf(
                PersistenceReadResult.Found.class, result
        );
        assertEquals(List.of(relevant), found.value().events());
        assertEquals(2, sequencePositions.get());
        assertEquals(1, relevantRows.get());
    }

    private ProjectionEvent append(long revision) throws Exception {
        return append(revision, "profile_changed");
    }

    private ProjectionEvent append(long revision, String eventType)
            throws Exception {
        OperationId operationId = OperationId.parse(String.format(
                "40000000-0000-0000-0000-%012d", revision
        ));
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new SqliteOperationStore(connection).prepare(
                    new PreparedOperation(
                            operationId,
                            new IdempotencyKey("projection-race-" + revision),
                            new OperationKind("projection_race_test"),
                            1,
                            "{}",
                            "test",
                            null,
                            List.of(),
                            -10_000 + revision
                    )
            );
            ProjectionEvent event = new SqliteProjectionOutboxStore(connection)
                    .append(new ProjectionEventDraft(
                            operationId,
                            new ProjectionEventType(eventType),
                            "profile-" + revision,
                            revision,
                            1,
                            "{\"revision\":" + revision + "}",
                            -10_000 + revision
                    )).value();
            connection.commit();
            return event;
        }
    }

    private void appendUnchecked(long revision) {
        try {
            append(revision);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Concurrent test append failed", failure
            );
        }
    }
}
