package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Reads due bonded cleanup work and durably records recovery outcomes. */
final class SqliteBondedCompanionCleanupQueue {
    private static final long RETRY_DELAY_MS = 1_000L;
    private final SqliteConnectionFactory connections;

    SqliteBondedCompanionCleanupQueue(SqliteConnectionFactory connections) {
        this.connections = connections;
    }

    List<BondedCompanionProjectionCleanupService.CleanupIntent> pending(
            long nowMs,
            int limit
    ) {
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT cleanup_id, owner_uuid, roster_id, profile_id,
                            lease_token, target_kind, target_npc_uuid,
                            world_key, cleanup_reason, created_at_ms,
                            retained_until_ms
                     FROM bonded_companion_cleanup
                     WHERE cleanup_state = 'PENDING' AND next_attempt_at_ms <= ?
                     ORDER BY next_attempt_at_ms, cleanup_id LIMIT ?
                     """)) {
            statement.setLong(1, nowMs);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                return readPending(rows);
            }
        } catch (Exception failure) {
            return List.of();
        }
    }

    void recordOutcome(
            String cleanupId,
            BondedCompanionProjectionCleanupService.Outcome outcome,
            long nowMs
    ) {
        String state = switch (outcome) {
            case REMOVED, ALREADY_MISSING -> "COMPLETED";
            case IDENTITY_MISMATCH -> "ABANDONED";
            case RETRY_REQUIRED -> "PENDING";
        };
        long next = outcome == BondedCompanionProjectionCleanupService.Outcome
                .RETRY_REQUIRED ? safeAdd(nowMs, RETRY_DELAY_MS) : nowMs;
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE bonded_companion_cleanup
                     SET cleanup_state = ?, attempt_count = attempt_count + 1,
                         next_attempt_at_ms = ?
                     WHERE cleanup_id = ? AND cleanup_state = 'PENDING'
                     """)) {
            update.setString(1, state);
            update.setLong(2, next);
            update.setString(3, cleanupId);
            update.executeUpdate();
        } catch (Exception ignored) {
            // The pending row remains durable for a later replay.
        }
    }

    private static List<BondedCompanionProjectionCleanupService.CleanupIntent>
    readPending(ResultSet rows) throws Exception {
        ArrayList<BondedCompanionProjectionCleanupService.CleanupIntent> result =
                new ArrayList<>();
        while (rows.next()) {
            result.add(new BondedCompanionProjectionCleanupService.CleanupIntent(
                    rows.getString(1), UUID.fromString(rows.getString(2)),
                    rows.getString(3), rows.getString(4), rows.getString(5),
                    BondedCompanionProjectionCleanupService.Target.valueOf(
                            rows.getString(6)),
                    UUID.fromString(rows.getString(7)), rows.getString(8),
                    rows.getString(9), rows.getLong(10), rows.getLong(11)
            ));
        }
        return List.copyOf(result);
    }

    private static long safeAdd(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
