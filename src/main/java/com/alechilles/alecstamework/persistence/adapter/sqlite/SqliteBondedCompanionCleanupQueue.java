package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reads due bonded cleanup work and durably records recovery outcomes. */
final class SqliteBondedCompanionCleanupQueue {
    private static final long INITIAL_RETRY_DELAY_MS = 1_000L;
    private static final long MAX_RETRY_DELAY_MS = 3_600_000L;
    private final SqliteConnectionFactory connections;

    SqliteBondedCompanionCleanupQueue(SqliteConnectionFactory connections) {
        this.connections = connections;
    }

    List<BondedCompanionProjectionCleanupService.CleanupIntent> pendingForWorld(
            String worldKey,
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
                       AND world_key = ?
                     ORDER BY next_attempt_at_ms, cleanup_id LIMIT ?
                     """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, worldKey);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                return readPending(rows);
            }
        } catch (Exception failure) {
            return List.of();
        }
    }

    boolean hasPendingForWorld(String worldKey) {
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1 FROM bonded_companion_cleanup
                     WHERE cleanup_state = 'PENDING' AND world_key = ?
                     LIMIT 1
                     """)) {
            statement.setString(1, worldKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "bonded cleanup activity query failed", failure);
        }
    }

    void recordOutcome(
            BondedCompanionProjectionCleanupService.CleanupIntent intent,
            BondedCompanionProjectionCleanupService.Outcome outcome,
            long nowMs
    ) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(outcome, "outcome");
        String cleanupId = intent.cleanupId();
        try (Connection connection = connections.openWriterConnection()) {
            int attempts = pendingAttempts(connection, cleanupId);
            if (attempts < 0) return;
            boolean retentionExpired = nowMs >= intent.retainedUntilMs();
            String state = state(outcome, retentionExpired);
            long next = nextAttemptAt(
                    outcome, nowMs, attempts + 1, intent.retainedUntilMs()
            );
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE bonded_companion_cleanup
                    SET cleanup_state = ?, attempt_count = attempt_count + 1,
                        next_attempt_at_ms = ?
                    WHERE cleanup_id = ? AND cleanup_state = 'PENDING'
                      AND attempt_count = ?
                    """)) {
                update.setString(1, state);
                update.setLong(2, next);
                update.setString(3, cleanupId);
                update.setInt(4, attempts);
                update.executeUpdate();
            }
        } catch (Exception ignored) {
            // The pending row remains durable for a later replay.
        }
    }

    private int pendingAttempts(Connection connection, String cleanupId)
            throws Exception {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT attempt_count FROM bonded_companion_cleanup
                WHERE cleanup_id = ? AND cleanup_state = 'PENDING'
                """)) {
            query.setString(1, cleanupId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
    }

    private static String state(
            BondedCompanionProjectionCleanupService.Outcome outcome,
            boolean retentionExpired
    ) {
        String state = switch (outcome) {
            case REMOVED, ALREADY_MISSING -> "COMPLETED";
            case IDENTITY_MISMATCH -> "ABANDONED";
            case RETRY_REQUIRED -> retentionExpired ? "ABANDONED" : "PENDING";
        };
        return state;
    }

    private static long nextAttemptAt(
            BondedCompanionProjectionCleanupService.Outcome outcome,
            long nowMs,
            int attemptNumber,
            long retainedUntilMs
    ) {
        if (outcome != BondedCompanionProjectionCleanupService.Outcome
                .RETRY_REQUIRED || nowMs >= retainedUntilMs) {
            return nowMs;
        }
        return Math.min(retainedUntilMs, safeAdd(nowMs, retryDelay(attemptNumber)));
    }

    private static long retryDelay(int attemptNumber) {
        int exponent = Math.min(Math.max(0, attemptNumber - 1), 12);
        return Math.min(MAX_RETRY_DELAY_MS, INITIAL_RETRY_DELAY_MS << exponent);
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
