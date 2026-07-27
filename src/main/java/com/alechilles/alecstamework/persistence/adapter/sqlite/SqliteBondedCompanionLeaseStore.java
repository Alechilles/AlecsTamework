package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Transaction-local authority for the sole live lease of a bonded profile. */
final class SqliteBondedCompanionLeaseStore {
    private final Connection connection;

    SqliteBondedCompanionLeaseStore(Connection connection) {
        this.connection = connection;
    }

    SqliteBondedCompanionStore.MutationResult<SqliteBondedCompanionLeaseRow>
            acquire(
                    UUID ownerUuid,
                    String rosterId,
                    long expectedRevision,
                    SqliteBondedCompanionLeaseRow lease
            ) throws SQLException {
        Scope scope = scope(lease.profileId(), ownerUuid, rosterId);
        if (scope != Scope.ALLOWED) {
            return denied(scope);
        }
        SqliteBondedCompanionProfileRow profile = profile(lease.profileId());
        if (profile.revision() != expectedRevision) {
            return result(SqliteBondedCompanionStore.MutationCode.REVISION_CONFLICT,
                    null, "profile-revision-conflict");
        }
        if (profile.state() != BondedCompanionState.STORED) {
            return result(SqliteBondedCompanionStore.MutationCode.INVALID_STATE,
                    null, "lease-requires-stored-profile");
        }
        insert(lease);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_companion_profile
                SET state = 'ACTIVE', revision = revision + 1,
                    updated_at_ms = ?
                WHERE profile_id = ? AND state = 'STORED' AND revision = ?
                """)) {
            statement.setLong(1, lease.startedAtMs());
            statement.setString(2, lease.profileId());
            statement.setLong(3, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("bonded_profile_lease_race");
            }
        }
        return result(SqliteBondedCompanionStore.MutationCode.APPLIED, lease, null);
    }

    SqliteBondedCompanionStore.MutationResult<SqliteBondedCompanionProfileRow>
            release(
                    UUID ownerUuid,
                    String rosterId,
                    String profileId,
                    String leaseToken,
                    long expectedRevision,
                    long updatedAtMs
            ) throws SQLException {
        Scope scope = scope(profileId, ownerUuid, rosterId);
        if (scope != Scope.ALLOWED) {
            return denied(scope);
        }
        SqliteBondedCompanionProfileRow current = profile(profileId);
        if (current.revision() != expectedRevision) {
            return result(SqliteBondedCompanionStore.MutationCode.REVISION_CONFLICT,
                    current, "profile-revision-conflict");
        }
        if (current.state() != BondedCompanionState.ACTIVE) {
            return result(SqliteBondedCompanionStore.MutationCode.INVALID_STATE,
                    current, "release-requires-active-profile");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM bonded_companion_lease
                WHERE profile_id = ? AND lease_token = ?
                """)) {
            statement.setString(1, current.profileId());
            statement.setString(2, leaseToken);
            if (statement.executeUpdate() != 1) {
                return result(SqliteBondedCompanionStore.MutationCode.CONFLICT,
                        current, "lease-token-conflict");
            }
        }
        deleteAdmission(current.profileId(), leaseToken);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_companion_profile
                SET state = 'STORED', revision = revision + 1, updated_at_ms = ?
                WHERE profile_id = ? AND revision = ?
                """)) {
            statement.setLong(1, updatedAtMs);
            statement.setString(2, profileId);
            statement.setLong(3, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("bonded_profile_revision_race");
            }
        }
        return result(SqliteBondedCompanionStore.MutationCode.APPLIED,
                profile(profileId), null);
    }

    List<SqliteBondedCompanionLeaseRow> expired(long nowMs, int limit)
            throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id, lease_token, live_npc_uuid, world_key,
                       started_at_ms, expires_at_ms, projection_state
                FROM bonded_companion_lease
                WHERE expires_at_ms <> 0 AND expires_at_ms <= ?
                ORDER BY expires_at_ms, profile_id LIMIT ?
                """)) {
            statement.setLong(1, nowMs);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<SqliteBondedCompanionLeaseRow> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(SqliteBondedCompanionRows.readLease(rows));
                }
                return List.copyOf(result);
            }
        }
    }

    private void insert(SqliteBondedCompanionLeaseRow row) throws SQLException {
        insertAdmission(row);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bonded_companion_lease(
                    profile_id, lease_token, live_npc_uuid, world_key,
                    started_at_ms, expires_at_ms, projection_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, row.profileId());
            statement.setString(2, row.leaseToken());
            statement.setString(3, row.liveNpcUuid().toString());
            statement.setString(4, row.worldKey());
            statement.setLong(5, row.startedAtMs());
            statement.setLong(6, row.expiresAtMs());
            statement.setString(7, row.projectionState());
            statement.executeUpdate();
        }
    }

    private void insertAdmission(SqliteBondedCompanionLeaseRow row)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bonded_companion_lease_admission(
                    profile_id, lease_token, admitted_at_ms
                ) VALUES (?, ?, ?)
                """)) {
            statement.setString(1, row.profileId());
            statement.setString(2, row.leaseToken());
            statement.setLong(3, row.startedAtMs());
            statement.executeUpdate();
        }
    }

    private void deleteAdmission(String profileId, String leaseToken)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM bonded_companion_lease_admission
                WHERE profile_id = ? AND lease_token = ?
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, leaseToken);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("bonded_lease_admission_missing");
            }
        }
    }

    private SqliteBondedCompanionProfileRow profile(String profileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                PROFILE_SELECT + " WHERE profile_id = ?"
        )) {
            statement.setString(1, profileId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("bonded_profile_disappeared");
                }
                return SqliteBondedCompanionRows.readProfile(row);
            }
        }
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

    private <T> SqliteBondedCompanionStore.MutationResult<T> denied(Scope scope) {
        return result(scope == Scope.NOT_FOUND
                        ? SqliteBondedCompanionStore.MutationCode.NOT_FOUND
                        : SqliteBondedCompanionStore.MutationCode.NOT_OWNER,
                null, scope == Scope.NOT_FOUND
                        ? "profile-not-found" : "profile-scope-mismatch");
    }

    private <T> SqliteBondedCompanionStore.MutationResult<T> result(
            SqliteBondedCompanionStore.MutationCode code,
            T value,
            String reason
    ) {
        return new SqliteBondedCompanionStore.MutationResult<>(code, value, reason);
    }

    private enum Scope { ALLOWED, NOT_FOUND, NOT_OWNER }

    private static final String PROFILE_SELECT = """
            SELECT profile_id, owner_uuid, roster_id, family_id, role_id,
                   state, revision, snapshot_json, created_at_ms, updated_at_ms,
                   policy_json, display_name, species, gender, died_at_ms,
                   summon_cooldown_until_ms, revive_count, quarantine_reason,
                   quarantined_at_ms
            FROM bonded_companion_profile
            """;
}
