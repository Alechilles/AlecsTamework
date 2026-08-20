package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV2Manager;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Creates a new schema off-path and publishes it only after complete verification. */
final class FreshReplacementTargetCreator {
    private final LongSupplier clock;
    private final ImportTargetPublisher publisher = new ImportTargetPublisher();

    FreshReplacementTargetCreator(LongSupplier clock) {
        this.clock = clock;
    }

    void create(Path target) throws Exception {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Replacement target requires a parent directory"
            );
        }
        Files.createDirectories(parent);
        Path temporary = target.resolveSibling(
                target.getFileName() + ".creating." + UUID.randomUUID()
        );
        try {
            SqliteConnectionFactory connections =
                    new SqliteConnectionFactory(temporary);
            SqliteSchemaV2Manager schemas =
                    new SqliteSchemaV2Manager(connections, clock);
            PersistenceTransactionResult<?> initialized =
                    schemas.initialize();
            if (!(initialized instanceof PersistenceTransactionResult.Committed<?>)
                    && !(initialized instanceof PersistenceTransactionResult.Unknown<?>
                    && schemas.verify() instanceof PersistenceReadResult.Found<?>)) {
                throw new IllegalStateException(
                        "fresh_replacement_schema_initialization_failed"
                );
            }
            checkpoint(connections);
            if (!(schemas.verify() instanceof PersistenceReadResult.Found<?>)) {
                throw new IllegalStateException(
                        "fresh_replacement_schema_verification_failed"
                );
            }
            publisher.publish(temporary, target);
        } finally {
            deleteOwnedAttempt(temporary);
        }
    }

    private void checkpoint(SqliteConnectionFactory connections)
            throws Exception {
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "PRAGMA wal_checkpoint(TRUNCATE)"
             )) {
            if (!row.next() || row.getInt(1) != 0) {
                throw new IllegalStateException(
                        "fresh_replacement_checkpoint_busy"
                );
            }
        }
    }

    private void deleteOwnedAttempt(Path temporary) {
        for (Path owned : new Path[]{
                temporary,
                temporary.resolveSibling(temporary.getFileName() + "-wal"),
                temporary.resolveSibling(temporary.getFileName() + "-shm")
        }) {
            try {
                Files.deleteIfExists(owned);
            } catch (Exception ignored) {
                // The unique attempt is never considered a canonical target.
            }
        }
    }
}
