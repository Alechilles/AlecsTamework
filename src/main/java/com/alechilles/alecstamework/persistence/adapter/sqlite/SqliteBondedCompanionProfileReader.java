package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns deterministic owner/roster/family profile reads for one transaction. */
final class SqliteBondedCompanionProfileReader {
    private final Connection connection;

    SqliteBondedCompanionProfileReader(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    List<SqliteBondedCompanionProfileRow> list(UUID ownerUuid, String rosterId) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        String roster = text(rosterId, "rosterId");
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE owner_uuid = ? AND roster_id = ?"
                        + " ORDER BY profile_id")) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, roster);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<SqliteBondedCompanionProfileRow> result =
                        new ArrayList<>();
                while (rows.next()) {
                    result.add(SqliteBondedCompanionRows.readProfile(rows));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw storageFailure("list-bonded-profiles", failure);
        }
    }

    Optional<SqliteBondedCompanionProfileRow> find(
            UUID ownerUuid,
            String rosterId,
            String profileId
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE profile_id = ?"
                        + " AND owner_uuid = ? AND roster_id = ?")) {
            statement.setString(1, text(profileId, "profileId"));
            statement.setString(2, ownerUuid.toString());
            statement.setString(3, text(rosterId, "rosterId"));
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(SqliteBondedCompanionRows.readProfile(row))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw storageFailure("find-bonded-profile", failure);
        }
    }

    Optional<SqliteBondedCompanionProfileRow> find(
            UUID ownerUuid,
            String profileId
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE profile_id = ? AND owner_uuid = ?")) {
            statement.setString(1, text(profileId, "profileId"));
            statement.setString(2, ownerUuid.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(SqliteBondedCompanionRows.readProfile(row))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw storageFailure("find-owned-bonded-profile", failure);
        }
    }

    SqliteBondedCompanionProfileRow require(String profileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE profile_id = ?")) {
            statement.setString(1, text(profileId, "profileId"));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("bonded_profile_disappeared");
                }
                return SqliteBondedCompanionRows.readProfile(row);
            }
        }
    }

    long countFamily(UUID ownerUuid, String rosterId, String familyId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM bonded_companion_profile
                WHERE owner_uuid = ? AND roster_id = ? AND family_id = ?
                """)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, rosterId);
            statement.setString(3, familyId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw storageFailure("count-bonded-profile-family", failure);
        }
    }

    private String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private IllegalStateException storageFailure(
            String operation,
            SQLException failure
    ) {
        return new IllegalStateException(operation + " failed", failure);
    }

    private static final String SELECT = """
            SELECT profile_id, owner_uuid, roster_id, family_id, role_id,
                   state, revision, snapshot_json, created_at_ms, updated_at_ms,
                   policy_json, display_name, species, gender, died_at_ms,
                   revive_cooldown_until_ms, revive_count, quarantine_reason,
                   quarantined_at_ms
            FROM bonded_companion_profile
            """;
}
