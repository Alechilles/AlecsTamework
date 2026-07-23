package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.annotation.Nonnull;

/** Runs a verified replacement WAL checkpoint after writer drain. */
public final class SqliteCheckpointService {
    private final SqliteConnectionFactory connections;

    public SqliteCheckpointService(@Nonnull SqliteConnectionFactory connections) {
        if (connections == null) {
            throw new IllegalArgumentException("SQLite checkpoint connection factory is required");
        }
        this.connections = connections;
    }

    /** Checkpoints and truncates the WAL, returning busy as an explicit retryable failure. */
    @Nonnull
    public SqliteCheckpointResult checkpoint() {
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!resultSet.next()) {
                return failed(new StorageFailure(
                        StorageFailureKind.UNKNOWN,
                        "sqlite_checkpoint_no_result",
                        "wal_checkpoint",
                        false,
                        null
                ));
            }
            int busy = resultSet.getInt(1);
            int logFrames = resultSet.getInt(2);
            int checkpointedFrames = resultSet.getInt(3);
            if (busy != 0) {
                return failed(new StorageFailure(
                        StorageFailureKind.BUSY,
                        "sqlite_checkpoint_busy",
                        "wal_checkpoint",
                        true,
                        null
                ));
            }
            return new SqliteCheckpointResult.Completed(logFrames, checkpointedFrames);
        } catch (Throwable failure) {
            return failed(SqliteFailureClassifier.classify(failure, "wal_checkpoint"));
        }
    }

    private SqliteCheckpointResult failed(StorageFailure failure) {
        return new SqliteCheckpointResult.Failed(failure);
    }
}
