package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Owns UUID-claim conflict checks and claim-kind transitions for managed residents. */
final class ManagedCoopUuidClaimStore {
    boolean canClaim(Connection connection, String residentId, UUID npcUuid) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT resident_id FROM managed_coop_uuid_claims WHERE npc_uuid = ?")) {
            query.setString(1, npcUuid.toString());
            try (ResultSet resultSet = query.executeQuery()) {
                return !resultSet.next() || residentId.equals(resultSet.getString(1));
            }
        }
    }

    boolean hasActive(Connection connection,
                      String residentId,
                      UUID npcUuid,
                      String kind) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM managed_coop_uuid_claims
                WHERE npc_uuid = ? AND resident_id = ? AND claim_kind = ? AND active = 1 LIMIT 1
                """)) {
            query.setString(1, npcUuid.toString());
            query.setString(2, residentId);
            query.setString(3, kind);
            try (ResultSet resultSet = query.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    void claim(Connection connection,
               String residentId,
               String kind,
               UUID npcUuid,
               long nowMs) throws SQLException {
        try (PreparedStatement deactivate = connection.prepareStatement("""
                UPDATE managed_coop_uuid_claims SET active = 0, updated_at_ms = ?
                WHERE resident_id = ? AND claim_kind = ? AND npc_uuid <> ? AND active = 1
                """)) {
            deactivate.setLong(1, nowMs);
            deactivate.setString(2, residentId);
            deactivate.setString(3, kind);
            deactivate.setString(4, npcUuid.toString());
            deactivate.executeUpdate();
        }
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO managed_coop_uuid_claims (
                    npc_uuid, resident_id, claim_kind, active, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, 1, ?, ?)
                ON CONFLICT(npc_uuid) DO UPDATE SET
                    claim_kind = excluded.claim_kind, active = 1, updated_at_ms = excluded.updated_at_ms
                WHERE managed_coop_uuid_claims.resident_id = excluded.resident_id
                """)) {
            upsert.setString(1, npcUuid.toString());
            upsert.setString(2, residentId);
            upsert.setString(3, kind);
            upsert.setLong(4, nowMs);
            upsert.setLong(5, nowMs);
            if (upsert.executeUpdate() != 1) {
                throw new SQLException("uuid_claim_conflict");
            }
        }
    }

    void deactivateKind(Connection connection,
                        String residentId,
                        String kind,
                        long nowMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_coop_uuid_claims SET active = 0, updated_at_ms = ?
                WHERE resident_id = ? AND claim_kind = ? AND active = 1
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, residentId);
            statement.setString(3, kind);
            statement.executeUpdate();
        }
    }

    void deactivateAll(Connection connection, String residentId, long nowMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_coop_uuid_claims SET active = 0, updated_at_ms = ?
                WHERE resident_id = ? AND active = 1
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, residentId);
            statement.executeUpdate();
        }
    }
}
