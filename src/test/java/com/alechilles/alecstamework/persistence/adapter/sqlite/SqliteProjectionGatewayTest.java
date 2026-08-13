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
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

    private ProjectionEvent append(long revision) throws Exception {
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
                            new ProjectionEventType("profile_changed"),
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
