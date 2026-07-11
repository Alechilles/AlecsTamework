package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.readCoverage;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setText;

/**
 * Persists bounded reconciliation coverage and resume cursors.
 */
public final class CompanionPopulationCoverageRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public CompanionPopulationCoverageRepository(@Nonnull SqliteConnectionManager connectionManager,
                                                 @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Void> upsertAsync(
            @Nonnull CompanionPopulationCoverageRecord coverage
    ) {
        return writeQueue.submitTracked(
                "companion_population_coverage_upsert",
                connection -> {
                    upsertInTransaction(connection, coverage);
                    return null;
                },
                null
        );
    }

    @Nonnull
    public List<CompanionPopulationCoverageRecord> loadAll() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT coverage_key, coverage_dimension, world_or_save_id, scan_generation,
                            state, cursor_json, scanned_count, estimated_total,
                            started_at_ms, updated_at_ms, completed_at_ms, last_error
                     FROM companion_population_reconciliation
                     ORDER BY coverage_key
                     """
             );
             ResultSet resultSet = statement.executeQuery()) {
            List<CompanionPopulationCoverageRecord> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(readCoverage(resultSet));
            }
            return List.copyOf(rows);
        }
    }

    private void upsertInTransaction(@Nonnull Connection connection,
                                     @Nonnull CompanionPopulationCoverageRecord coverage) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO companion_population_reconciliation (
                    coverage_key, coverage_dimension, world_or_save_id, scan_generation,
                    state, cursor_json, scanned_count, estimated_total,
                    started_at_ms, updated_at_ms, completed_at_ms, last_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(coverage_key) DO UPDATE SET
                    coverage_dimension = excluded.coverage_dimension,
                    world_or_save_id = excluded.world_or_save_id,
                    scan_generation = excluded.scan_generation,
                    state = excluded.state,
                    cursor_json = excluded.cursor_json,
                    scanned_count = excluded.scanned_count,
                    estimated_total = excluded.estimated_total,
                    started_at_ms = excluded.started_at_ms,
                    updated_at_ms = excluded.updated_at_ms,
                    completed_at_ms = excluded.completed_at_ms,
                    last_error = excluded.last_error
                """
        )) {
            statement.setString(1, coverage.coverageKey());
            statement.setString(2, coverage.dimension().name());
            setText(statement, 3, coverage.worldOrSaveId());
            statement.setString(4, coverage.scanGeneration());
            statement.setString(5, coverage.state().name());
            setText(statement, 6, coverage.cursorJson());
            statement.setLong(7, coverage.scannedCount());
            statement.setLong(8, coverage.estimatedTotal());
            statement.setLong(9, coverage.startedAtMs());
            statement.setLong(10, coverage.updatedAtMs());
            statement.setLong(11, coverage.completedAtMs());
            setText(statement, 12, coverage.lastError());
            statement.executeUpdate();
        }
    }
}
