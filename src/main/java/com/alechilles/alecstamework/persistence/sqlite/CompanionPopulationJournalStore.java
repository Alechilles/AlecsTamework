package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.CompanionSpawnSourceFinalizationContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.readOperation;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setText;

/**
 * Encapsulates companion-population operation journal SQL, including recovery loads, legal state
 * transitions, and the APPLIED source-finalization barrier.
 */
final class CompanionPopulationJournalStore {
    private final SqliteConnectionManager connectionManager;

    CompanionPopulationJournalStore(@Nonnull SqliteConnectionManager connectionManager) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
    }

    @Nonnull
    List<CompanionPopulationOperationRecord> loadNonterminalOperations() throws Exception {
        return loadOperations("""
                WHERE state IN ('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING')
                ORDER BY created_at_ms, operation_id
                """);
    }

    @Nonnull
    List<CompanionPopulationOperationRecord> loadBreedingOperations() throws Exception {
        return loadOperations("""
                WHERE operation_type = 'BREEDING'
                ORDER BY created_at_ms, operation_id
                """);
    }

    @Nonnull
    private List<CompanionPopulationOperationRecord> loadOperations(@Nonnull String clause) throws Exception {
        String sql = """
                SELECT operation_id, profile_id, operation_type, state, expected_revision,
                       old_state_json, new_state_json, target_context_json,
                       created_at_ms, updated_at_ms, completed_at_ms, last_error
                FROM companion_population_operations
                """ + clause;
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<CompanionPopulationOperationRecord> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(readOperation(resultSet));
            }
            return List.copyOf(rows);
        }
    }

    void insert(@Nonnull Connection connection,
                @Nonnull CompanionPopulationOperationRecord operation) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO companion_population_operations (
                    operation_id, profile_id, operation_type, state, expected_revision,
                    old_state_json, new_state_json, target_context_json,
                    created_at_ms, updated_at_ms, completed_at_ms, last_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            statement.setString(1, operation.operationId());
            statement.setString(2, operation.profileId());
            statement.setString(3, operation.operationType());
            statement.setString(4, operation.state().name());
            statement.setLong(5, operation.expectedRevision());
            statement.setString(6, operation.oldStateJson());
            statement.setString(7, operation.newStateJson());
            setText(statement, 8, operation.targetContextJson());
            statement.setLong(9, operation.createdAtMs());
            statement.setLong(10, operation.updatedAtMs());
            statement.setLong(11, operation.completedAtMs());
            setText(statement, 12, operation.lastError());
            statement.executeUpdate();
        }
    }

    boolean advance(@Nonnull Connection connection,
                    @Nonnull String operationId,
                    @Nonnull CompanionPopulationOperationRecord.State expected,
                    @Nonnull CompanionPopulationOperationRecord.State next,
                    @Nullable String error) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE companion_population_operations
                SET state = ?, updated_at_ms = ?,
                    completed_at_ms = CASE
                        WHEN ? IN ('COMMITTED', 'RETRYABLE', 'FAILED') THEN ? ELSE 0 END,
                    last_error = ?
                WHERE operation_id = ? AND state = ?
                """
        )) {
            long now = System.currentTimeMillis();
            statement.setString(1, next.name());
            statement.setLong(2, now);
            statement.setString(3, next.name());
            statement.setLong(4, now);
            setText(statement, 5, error);
            statement.setString(6, operationId);
            statement.setString(7, expected.name());
            return statement.executeUpdate() == 1;
        }
    }

    void markApplied(@Nonnull Connection connection, @Nonnull String operationId) throws Exception {
        if (!advance(
                connection,
                operationId,
                CompanionPopulationOperationRecord.State.APPLYING,
                CompanionPopulationOperationRecord.State.APPLIED,
                "source_finalization_pending"
        )) {
            throw new IllegalStateException("Population operation changed before source finalization.");
        }
    }

    void finalizeCommitted(@Nonnull Connection connection, @Nonnull String operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE companion_population_operations
                SET state = 'COMMITTED', updated_at_ms = ?, completed_at_ms = ?, last_error = NULL
                WHERE operation_id = ? AND state IN ('APPLYING', 'APPLIED')
                """
        )) {
            long now = System.currentTimeMillis();
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, operationId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Population operation changed during commit.");
            }
        }
    }

    boolean completeSourceFinalization(
            @Nonnull Connection connection,
            @Nonnull String operationId
    ) throws Exception {
        OperationIdentity operation = find(connection, operationId);
        if (operation == null) {
            return false;
        }
        if (operation.state() == CompanionPopulationOperationRecord.State.COMMITTED) {
            return true;
        }
        if (operation.state() != CompanionPopulationOperationRecord.State.APPLIED
                || !CompanionSpawnSourceFinalizationContext.required(operation.targetContextJson())) {
            return false;
        }
        finalizeCommitted(connection, operationId);
        return true;
    }

    @Nullable
    OperationIdentity find(@Nonnull Connection connection, @Nonnull String operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT profile_id, state, expected_revision, target_context_json "
                        + "FROM companion_population_operations WHERE operation_id = ?"
        )) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new OperationIdentity(
                        resultSet.getString(1),
                        CompanionPopulationOperationRecord.State.valueOf(resultSet.getString(2)),
                        resultSet.getLong(3),
                        resultSet.getString(4)
                );
            }
        }
    }

    boolean hasNonterminal(@Nonnull Connection connection, @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT 1 FROM companion_population_operations
                WHERE profile_id = ? AND state IN ('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING')
                LIMIT 1
                """
        )) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    record OperationIdentity(@Nonnull String profileId,
                             @Nonnull CompanionPopulationOperationRecord.State state,
                             long expectedRevision,
                             @Nullable String targetContextJson) {
    }
}
