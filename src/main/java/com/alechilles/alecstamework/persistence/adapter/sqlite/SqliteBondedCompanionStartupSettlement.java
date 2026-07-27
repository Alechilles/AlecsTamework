package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Database-only startup settlement for every residual bonded lease. */
final class SqliteBondedCompanionStartupSettlement {
    private final SqliteConnectionFactory connections;

    SqliteBondedCompanionStartupSettlement(
            SqliteConnectionFactory connections
    ) {
        this.connections = connections;
    }

    int settle(long nowMs) {
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            int residual = count(connection);
            if (residual == 0) {
                connection.commit();
                return 0;
            }
            insertCleanup(connection, nowMs, retainedUntil(nowMs));
            if (storeProfiles(connection, nowMs) != residual) {
                throw new IllegalStateException("residual lease/profile mismatch");
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM bonded_companion_lease")) {
                if (delete.executeUpdate() != residual) {
                    throw new IllegalStateException("residual lease delete mismatch");
                }
            }
            connection.commit();
            return residual;
        } catch (Exception failure) {
            rollback(connection);
            throw new IllegalStateException(
                    "bonded startup residual settlement failed", failure);
        } finally {
            close(connection);
        }
    }

    private int count(Connection connection) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM bonded_companion_lease");
             ResultSet row = query.executeQuery()) {
            return row.next() ? row.getInt(1) : 0;
        }
    }

    private void insertCleanup(
            Connection connection,
            long nowMs,
            long retainedUntilMs
    ) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO bonded_companion_cleanup(
                    cleanup_id, owner_uuid, roster_id, profile_id,
                    lease_token, target_kind, target_npc_uuid, cleanup_reason,
                    world_key, cleanup_state, attempt_count, next_attempt_at_ms,
                    created_at_ms, retained_until_ms
                )
                SELECT 'startup:' || l.profile_id || ':' || l.lease_token || ':'
                           || l.live_npc_uuid,
                       p.owner_uuid, p.roster_id, l.profile_id, l.lease_token,
                       'PROJECTION', l.live_npc_uuid, 'startup-residual',
                       l.world_key, 'PENDING', 0, ?, ?, ?
                FROM bonded_companion_lease l
                JOIN bonded_companion_profile p ON p.profile_id = l.profile_id
                WHERE NOT EXISTS (
                    SELECT 1 FROM bonded_companion_cleanup c
                    WHERE c.profile_id = l.profile_id
                      AND c.lease_token = l.lease_token
                      AND c.target_kind = 'PROJECTION'
                      AND c.target_npc_uuid = l.live_npc_uuid
                      AND c.world_key = l.world_key
                      AND c.cleanup_state = 'PENDING'
                )
                """)) {
            insert.setLong(1, nowMs);
            insert.setLong(2, nowMs);
            insert.setLong(3, retainedUntilMs);
            insert.executeUpdate();
        }
    }

    private int storeProfiles(Connection connection, long nowMs)
            throws Exception {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE bonded_companion_profile
                SET state = 'STORED', revision = revision + 1,
                    died_at_ms = NULL, updated_at_ms = ?
                WHERE state = 'ACTIVE' AND EXISTS (
                    SELECT 1 FROM bonded_companion_lease l
                    WHERE l.profile_id = bonded_companion_profile.profile_id
                )
                """)) {
            update.setLong(1, nowMs);
            return update.executeUpdate();
        }
    }

    private long retainedUntil(long nowMs) {
        try {
            long retained = Math.addExact(nowMs,
                    BondedCompanionProjectionCleanupService.CLEANUP_RETENTION_MS);
            return retained == 0L ? 1L : retained;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private void rollback(Connection connection) {
        if (connection == null) return;
        try { connection.rollback(); } catch (Exception ignored) { }
    }

    private void close(Connection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (Exception ignored) { }
    }
}
