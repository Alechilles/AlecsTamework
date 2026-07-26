package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns bounded bonded cleanup intents and idempotency-record retention. */
final class SqliteBondedCompanionRetentionStore {
    private final Connection connection;

    SqliteBondedCompanionRetentionStore(Connection connection) {
        this.connection = connection;
    }

    SqliteBondedCompanionStore.MutationResult<SqliteBondedCompanionCleanupRow>
            enqueueCleanup(
                    UUID ownerUuid,
                    String rosterId,
                    SqliteBondedCompanionCleanupRow row
            ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(row, "row");
        if (!row.ownerUuid().equals(ownerUuid)
                || !row.rosterId().equals(requireText(rosterId, "rosterId"))) {
            return result(SqliteBondedCompanionStore.MutationCode.NOT_OWNER,
                    null, "cleanup-scope-mismatch");
        }
        return write(connection -> {
            SqliteBondedCompanionStore.MutationCode scope = scope(
                    connection, row.profileId(), ownerUuid, rosterId
            );
            if (scope != SqliteBondedCompanionStore.MutationCode.APPLIED) {
                return result(scope, null, scopeReason(scope));
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bonded_companion_cleanup(
                        cleanup_id, owner_uuid, roster_id, profile_id,
                        lease_token, target_kind, target_npc_uuid,
                        cleanup_reason, cleanup_state, attempt_count,
                        next_attempt_at_ms, created_at_ms, retained_until_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                SqliteBondedCompanionRows.bindCleanup(statement, row);
                statement.executeUpdate();
            }
            return result(SqliteBondedCompanionStore.MutationCode.APPLIED,
                    row, null);
        });
    }

    List<SqliteBondedCompanionCleanupRow> listCleanup(
            UUID ownerUuid,
            String rosterId,
            int limit
    ) {
        requirePositiveLimit(limit);
        try (PreparedStatement statement = connection.prepareStatement("""
                     SELECT cleanup_id, owner_uuid, roster_id, profile_id,
                            lease_token, target_kind, target_npc_uuid,
                            cleanup_reason, cleanup_state, attempt_count,
                            next_attempt_at_ms, created_at_ms, retained_until_ms
                     FROM bonded_companion_cleanup
                     WHERE owner_uuid = ? AND roster_id = ?
                     ORDER BY created_at_ms, cleanup_id LIMIT ?
                     """)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, requireText(rosterId, "rosterId"));
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<SqliteBondedCompanionCleanupRow> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(SqliteBondedCompanionRows.readCleanup(rows));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw storageFailure("list-bonded-cleanup", failure);
        }
    }

    int pruneCleanup(long nowMs, int limit) {
        return prune("""
                DELETE FROM bonded_companion_cleanup WHERE cleanup_id IN (
                    SELECT cleanup_id FROM bonded_companion_cleanup
                    WHERE cleanup_state IN ('COMPLETED', 'ABANDONED')
                      AND retained_until_ms <= ?
                    ORDER BY retained_until_ms, cleanup_id LIMIT ?
                )
                """, nowMs, limit, "prune-bonded-cleanup");
    }

    SqliteBondedCompanionStore.MutationResult<SqliteBondedCompanionOperationRow>
            recordOperation(SqliteBondedCompanionOperationRow row) {
        Objects.requireNonNull(row, "row");
        return write(connection -> {
            if (row.profileId() != null) {
                SqliteBondedCompanionStore.MutationCode scope = scope(
                        connection, row.profileId(), row.ownerUuid(), row.rosterId()
                );
                if (scope != SqliteBondedCompanionStore.MutationCode.APPLIED) {
                    return result(scope, null, scopeReason(scope));
                }
            }
            Optional<SqliteBondedCompanionOperationRow> existing = operation(
                    connection, row.callerNamespace(), row.idempotencyKey()
            );
            if (existing.isPresent()) {
                return existing.get().equals(row)
                        ? result(SqliteBondedCompanionStore.MutationCode.IDEMPOTENT_REPLAY,
                                existing.get(), "idempotent-replay")
                        : result(SqliteBondedCompanionStore.MutationCode.CONFLICT,
                                existing.get(), "idempotency-key-conflict");
            }
            insertOperation(connection, row);
            return result(SqliteBondedCompanionStore.MutationCode.APPLIED,
                    row, null);
        });
    }

    int pruneOperations(long nowMs, int limit) {
        return prune("""
                DELETE FROM bonded_companion_operation
                WHERE (caller_namespace, idempotency_key) IN (
                    SELECT caller_namespace, idempotency_key
                    FROM bonded_companion_operation
                    WHERE expires_at_ms <= ?
                    ORDER BY expires_at_ms, caller_namespace, idempotency_key
                    LIMIT ?
                )
                """, nowMs, limit, "prune-bonded-operations");
    }

    private Optional<SqliteBondedCompanionOperationRow> operation(
            Connection connection,
            String namespace,
            String key
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT caller_namespace, idempotency_key, owner_uuid, roster_id,
                       profile_id, operation_type, request_hash, operation_state,
                       result_json, created_at_ms, updated_at_ms, expires_at_ms
                FROM bonded_companion_operation
                WHERE caller_namespace = ? AND idempotency_key = ?
                """)) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(SqliteBondedCompanionRows.readOperation(row))
                        : Optional.empty();
            }
        }
    }

    private void insertOperation(Connection connection,
                                 SqliteBondedCompanionOperationRow row)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bonded_companion_operation(
                    caller_namespace, idempotency_key, owner_uuid, roster_id,
                    profile_id, operation_type, request_hash, operation_state,
                    result_json, created_at_ms, updated_at_ms, expires_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, row.callerNamespace());
            statement.setString(2, row.idempotencyKey());
            statement.setString(3, row.ownerUuid().toString());
            statement.setString(4, row.rosterId());
            statement.setString(5, row.profileId());
            statement.setString(6, row.operationType());
            statement.setString(7, row.requestHash());
            statement.setString(8, row.operationState());
            statement.setString(9, row.resultJson());
            statement.setLong(10, row.createdAtMs());
            statement.setLong(11, row.updatedAtMs());
            statement.setLong(12, row.expiresAtMs());
            statement.executeUpdate();
        }
    }

    private SqliteBondedCompanionStore.MutationCode scope(
            Connection connection,
            String profileId,
            UUID ownerUuid,
            String rosterId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_uuid, roster_id FROM bonded_companion_profile
                WHERE profile_id = ?
                """)) {
            statement.setString(1, requireText(profileId, "profileId"));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return SqliteBondedCompanionStore.MutationCode.NOT_FOUND;
                }
                return ownerUuid.toString().equals(row.getString("owner_uuid"))
                        && requireText(rosterId, "rosterId")
                        .equals(row.getString("roster_id"))
                        ? SqliteBondedCompanionStore.MutationCode.APPLIED
                        : SqliteBondedCompanionStore.MutationCode.NOT_OWNER;
            }
        }
    }

    private int prune(String sql, long nowMs, int limit, String operation) {
        requirePositiveLimit(limit);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nowMs);
            statement.setInt(2, limit);
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw storageFailure(operation, failure);
        }
    }

    private <T> SqliteBondedCompanionStore.MutationResult<T> write(
            SqlWrite<T> work
    ) {
        try {
            return work.execute(connection);
        } catch (SQLException failure) {
            return result(isConstraint(failure)
                            ? SqliteBondedCompanionStore.MutationCode.CONFLICT
                            : SqliteBondedCompanionStore.MutationCode.STORAGE_FAILURE,
                    null, "bonded-retention-write-failed");
        }
    }

    private <T> SqliteBondedCompanionStore.MutationResult<T> result(
            SqliteBondedCompanionStore.MutationCode code,
            T value,
            String reason
    ) {
        return new SqliteBondedCompanionStore.MutationResult<>(code, value, reason);
    }

    private String scopeReason(SqliteBondedCompanionStore.MutationCode code) {
        return code == SqliteBondedCompanionStore.MutationCode.NOT_FOUND
                ? "profile-not-found" : "profile-scope-mismatch";
    }

    private boolean isConstraint(SQLException failure) {
        String message = failure.getMessage();
        return message != null && message.toUpperCase(Locale.ROOT)
                .contains("CONSTRAINT");
    }

    private String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private IllegalStateException storageFailure(String operation,
                                                 SQLException failure) {
        return new IllegalStateException(operation, failure);
    }

    @FunctionalInterface
    private interface SqlWrite<T> {
        SqliteBondedCompanionStore.MutationResult<T> execute(Connection connection)
                throws SQLException;
    }
}
