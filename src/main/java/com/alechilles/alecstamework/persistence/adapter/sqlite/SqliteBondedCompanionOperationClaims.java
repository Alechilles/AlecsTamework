package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionOperationProbe;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/** Claims and compares exact bonded operation identities within a transaction. */
final class SqliteBondedCompanionOperationClaims {
    Claim claim(
            Connection connection,
            BondedCompanionOperation operation,
            @Nullable Long expectedRevision,
            String placeholderResult
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO bonded_companion_operation(
                    caller_namespace, idempotency_key, owner_uuid, roster_id,
                    profile_id, operation_type, request_hash, operation_state,
                    result_json, created_at_ms, updated_at_ms, expires_at_ms,
                    expected_revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'REJECTED', ?, ?, ?, ?, ?)
                """)) {
            bind(insert, operation, expectedRevision, placeholderResult);
            if (insert.executeUpdate() == 1) return new Claim(true, null);
        }
        return existing(connection, operation, expectedRevision).orElseThrow(
                () -> new SQLException("bonded_operation_claim_disappeared"));
    }

    Optional<Claim> existing(
            Connection connection,
            BondedCompanionOperation operation,
            @Nullable Long expectedRevision
    ) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT owner_uuid, roster_id, profile_id, operation_type,
                       request_hash, expected_revision, operation_state,
                       result_json
                FROM bonded_companion_operation
                WHERE caller_namespace = ? AND idempotency_key = ?
                """)) {
            select.setString(1, operation.callerNamespace());
            select.setString(2, operation.idempotencyKey());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) return Optional.empty();
                boolean matches = operation.ownerUuid().toString().equals(
                        row.getString(1))
                        && operation.rosterId().equals(row.getString(2))
                        && Objects.equals(
                        operation.profileId(), row.getString(3))
                        && operation.type().name().equals(row.getString(4))
                        && operation.requestHash().equals(row.getString(5))
                        && (expectedRevision == null || revisionMatches(
                        expectedRevision, nullableLong(row, 6)));
                return Optional.of(new Claim(
                        false, matches ? row.getString(8) : null,
                        matches, row.getString(7)));
            }
        }
    }

    Optional<Claim> existing(
            Connection connection,
            BondedCompanionOperation operation
    ) throws SQLException {
        return existing(connection, operation, null);
    }

    Optional<Claim> existing(
            Connection connection,
            BondedCompanionOperationProbe operation
    ) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT owner_uuid, roster_id, profile_id, operation_type,
                       expected_revision, operation_state, result_json
                FROM bonded_companion_operation
                WHERE caller_namespace = ? AND idempotency_key = ?
                """)) {
            select.setString(1, operation.callerNamespace());
            select.setString(2, operation.idempotencyKey());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) return Optional.empty();
                boolean matches = operation.ownerUuid().toString().equals(
                        row.getString(1))
                        && operation.rosterId().equals(row.getString(2))
                        && Objects.equals(
                        operation.profileId(), row.getString(3))
                        && operation.type().name().equals(row.getString(4))
                        && revisionMatches(operation.expectedRevision(),
                        nullableLong(row, 5));
                return Optional.of(new Claim(
                        false, matches ? row.getString(7) : null,
                        matches, row.getString(6)));
            }
        }
    }

    private void bind(
            PreparedStatement statement,
            BondedCompanionOperation operation,
            @Nullable Long expectedRevision,
            String placeholderResult
    ) throws SQLException {
        statement.setString(1, operation.callerNamespace());
        statement.setString(2, operation.idempotencyKey());
        statement.setString(3, operation.ownerUuid().toString());
        statement.setString(4, operation.rosterId());
        statement.setString(5, operation.profileId());
        statement.setString(6, operation.type().name());
        statement.setString(7, operation.requestHash());
        statement.setString(8, placeholderResult);
        statement.setLong(9, operation.attemptedAtMs());
        statement.setLong(10, operation.attemptedAtMs());
        statement.setLong(11, operation.retainedUntilMs());
        if (expectedRevision == null) {
            statement.setNull(12, Types.BIGINT);
        } else {
            statement.setLong(12, expectedRevision);
        }
    }

    private boolean revisionMatches(
            @Nullable Long expected,
            @Nullable Long stored
    ) {
        return Objects.equals(expected, stored);
    }

    private Long nullableLong(ResultSet row, int column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    record Claim(
            boolean created,
            String resultJson,
            boolean matches,
            String state
    ) {
        Claim(boolean created, String resultJson) {
            this(created, resultJson, true, "REJECTED");
        }
    }
}
