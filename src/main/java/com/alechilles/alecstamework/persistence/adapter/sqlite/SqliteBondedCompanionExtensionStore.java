package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transaction-local compare-and-set authority for bonded extension payloads. */
final class SqliteBondedCompanionExtensionStore {
    private final Connection connection;

    SqliteBondedCompanionExtensionStore(Connection connection) {
        this.connection = connection;
    }

    Optional<SqliteBondedCompanionExtensionDataRow> find(
            UUID ownerUuid,
            String rosterId,
            String profileId,
            String namespace
    ) throws SQLException {
        if (!owned(profileId, ownerUuid, rosterId)) {
            return Optional.empty();
        }
        return findExact(profileId, namespace);
    }

    List<SqliteBondedCompanionExtensionDataRow> list(
            UUID ownerUuid,
            String rosterId,
            String profileId
    ) throws SQLException {
        if (!owned(profileId, ownerUuid, rosterId)) {
            return List.of();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id, namespace, json_payload, revision, updated_at_ms
                FROM bonded_companion_extension_data
                WHERE profile_id = ?
                ORDER BY namespace
                """)) {
            statement.setString(1, profileId);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<SqliteBondedCompanionExtensionDataRow> result =
                        new ArrayList<>();
                while (rows.next()) {
                    result.add(SqliteBondedCompanionRows.readExtension(rows));
                }
                return List.copyOf(result);
            }
        }
    }

    SqliteBondedCompanionStore.MutationResult<SqliteBondedCompanionExtensionDataRow>
            compareAndSet(
                    UUID ownerUuid,
                    String rosterId,
                    SqliteBondedCompanionExtensionDataRow row,
                    long expectedRevision
            ) throws SQLException {
        Scope scope = scope(row.profileId(), ownerUuid, rosterId);
        if (scope != Scope.ALLOWED) {
            return result(scope == Scope.NOT_FOUND
                            ? SqliteBondedCompanionStore.MutationCode.NOT_FOUND
                            : SqliteBondedCompanionStore.MutationCode.NOT_OWNER,
                    null, scope == Scope.NOT_FOUND
                            ? "profile-not-found" : "profile-scope-mismatch");
        }
        Optional<SqliteBondedCompanionExtensionDataRow> current =
                findExact(row.profileId(), row.namespace());
        if (expectedRevision < 0) {
            if (current.isPresent() || row.revision() != 0) {
                return conflict(current);
            }
            insert(row);
        } else {
            if (current.isEmpty()
                    || current.get().revision() != expectedRevision
                    || row.revision() != expectedRevision + 1) {
                return conflict(current);
            }
            update(row, expectedRevision);
        }
        return result(SqliteBondedCompanionStore.MutationCode.APPLIED, row, null);
    }

    private Optional<SqliteBondedCompanionExtensionDataRow> findExact(
            String profileId,
            String namespace
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id, namespace, json_payload, revision, updated_at_ms
                FROM bonded_companion_extension_data
                WHERE profile_id = ? AND namespace = ?
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, namespace);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(SqliteBondedCompanionRows.readExtension(row))
                        : Optional.empty();
            }
        }
    }

    private void insert(SqliteBondedCompanionExtensionDataRow row)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bonded_companion_extension_data(
                    profile_id, namespace, json_payload, revision, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            SqliteBondedCompanionRows.bindExtension(statement, row);
            statement.executeUpdate();
        }
    }

    private void update(SqliteBondedCompanionExtensionDataRow row,
                        long expectedRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_companion_extension_data
                SET json_payload = ?, revision = ?, updated_at_ms = ?
                WHERE profile_id = ? AND namespace = ? AND revision = ?
                """)) {
            statement.setString(1, row.jsonPayload());
            statement.setLong(2, row.revision());
            statement.setLong(3, row.updatedAtMs());
            statement.setString(4, row.profileId());
            statement.setString(5, row.namespace());
            statement.setLong(6, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("bonded_extension_revision_race");
            }
        }
    }

    private boolean owned(String profileId, UUID ownerUuid, String rosterId)
            throws SQLException {
        return scope(profileId, ownerUuid, rosterId) == Scope.ALLOWED;
    }

    private Scope scope(String profileId, UUID ownerUuid, String rosterId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_uuid, roster_id FROM bonded_companion_profile
                WHERE profile_id = ?
                """)) {
            statement.setString(1, profileId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Scope.NOT_FOUND;
                }
                return ownerUuid.toString().equals(row.getString("owner_uuid"))
                        && rosterId.equals(row.getString("roster_id"))
                        ? Scope.ALLOWED : Scope.NOT_OWNER;
            }
        }
    }

    private SqliteBondedCompanionStore.MutationResult<SqliteBondedCompanionExtensionDataRow>
            conflict(Optional<SqliteBondedCompanionExtensionDataRow> current) {
        return result(SqliteBondedCompanionStore.MutationCode.REVISION_CONFLICT,
                current.orElse(null), "extension-revision-conflict");
    }

    private <T> SqliteBondedCompanionStore.MutationResult<T> result(
            SqliteBondedCompanionStore.MutationCode code,
            T value,
            String reason
    ) {
        return new SqliteBondedCompanionStore.MutationResult<>(code, value, reason);
    }

    private enum Scope { ALLOWED, NOT_FOUND, NOT_OWNER }
}
