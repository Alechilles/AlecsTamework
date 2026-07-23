package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Operation reader tests include nonterminal envelopes with no outbox evidence. */
class SqliteOperationReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void readsPreparedOperationByIdAndIdempotency() throws Exception {
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(tempDir.resolve("state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -100).initialize();
        OperationId operationId =
                OperationId.parse("10000000-0000-0000-0000-000000000001");
        OperationKind kind = new OperationKind("reader_test");
        IdempotencyKey key = new IdempotencyKey("reader-key");
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new SqliteOperationStore(connection).prepare(new PreparedOperation(
                    operationId,
                    key,
                    kind,
                    1,
                    "{}",
                    "reader_test",
                    null,
                    List.of(),
                    -90
            ));
            connection.commit();
        }
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        try {
            PersistenceReadResult<SqliteOperationReader.OperationReadModel> byId =
                    new SqliteOperationReader(reads).find(operationId)
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
            PersistenceReadResult<SqliteOperationReader.OperationReadModel> byKey =
                    new SqliteOperationReader(reads).findByIdempotency(kind, key)
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
            SqliteOperationReader.OperationReadModel byIdModel = assertInstanceOf(
                    SqliteOperationReader.OperationReadModel.class,
                    assertInstanceOf(PersistenceReadResult.Found.class, byId).value()
            );
            SqliteOperationReader.OperationReadModel byKeyModel = assertInstanceOf(
                    SqliteOperationReader.OperationReadModel.class,
                    assertInstanceOf(PersistenceReadResult.Found.class, byKey).value()
            );

            assertEquals(operationId, byIdModel.operation().operationId());
            assertEquals(operationId, byKeyModel.operation().operationId());
            assertEquals(List.of(), byKeyModel.events());
        } finally {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }
}
