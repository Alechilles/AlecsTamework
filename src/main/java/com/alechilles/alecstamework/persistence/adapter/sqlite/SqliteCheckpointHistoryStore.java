package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Connection-bound compaction of consumed, superseded internal entity checkpoints only. */
final class SqliteCheckpointHistoryStore {
    static final int BATCH_SIZE = 64;
    static final long RETENTION_MS = 60 * 60 * 1_000L;
    private final Connection connection;

    SqliteCheckpointHistoryStore(Connection connection) {
        this.connection = connection;
    }

    /** The sequence window bounds examined rows, including rows that cannot be compacted. */
    Batch select(long afterSequence, long nowMs) throws SQLException {
        ArrayList<Receipt> receipts = new ArrayList<>();
        long lastSequence = 0;
        int examined = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH scanned AS (
                    SELECT event_sequence, operation_id, aggregate_revision
                    FROM projection_outbox
                    WHERE event_type = 'profile_extension_mutated' AND event_sequence > ?
                    ORDER BY event_sequence LIMIT ?
                )
                SELECT e.event_sequence, o.operation_id,
                    CASE WHEN o.operation_kind = 'profile_extension_mutation'
                        AND o.idempotency_key GLOB 'companion-entity-checkpoint:v1:*'
                        AND o.phase = 'PUBLISHED' AND o.payload_version = 1
                        AND o.published_at_ms <= ?
                        AND json_extract(o.payload_json, '$.namespace') = ?
                        AND json_extract(o.payload_json, '$.action') = 'PUT'
                        AND d.revision > e.aggregate_revision
                        AND e.event_sequence <= COALESCE((
                            SELECT acknowledged_sequence FROM projection_checkpoint
                            WHERE consumer_id = 'profile_extension_index'), 0)
                        AND e.event_sequence < (SELECT MAX(event_sequence) FROM projection_outbox)
                        AND NOT EXISTS (
                            SELECT 1 FROM projection_outbox other
                            WHERE other.operation_id = o.operation_id
                                AND other.event_sequence <> e.event_sequence)
                        AND NOT EXISTS (
                            SELECT 1 FROM operation_participant p
                            JOIN persistence_quarantine q
                                ON q.scope_type = p.scope_type AND q.scope_key = p.scope_key
                            WHERE p.operation_id = o.operation_id AND q.state = 'ACTIVE')
                    THEN o.payload_json END AS compact_payload
                FROM scanned e JOIN operation_envelope o ON o.operation_id = e.operation_id
                LEFT JOIN profile_extension_data d
                    ON d.profile_id = json_extract(o.payload_json, '$.profileId')
                    AND d.namespace = json_extract(o.payload_json, '$.namespace')
                    AND d.data_key = json_extract(o.payload_json, '$.dataKey')
                ORDER BY e.event_sequence
                """)) {
            statement.setLong(1, afterSequence);
            statement.setInt(2, BATCH_SIZE);
            statement.setLong(3, nowMs < Long.MIN_VALUE + RETENTION_MS
                    ? Long.MIN_VALUE : nowMs - RETENTION_MS);
            statement.setString(4, SqliteCheckpointReceipt.NAMESPACE);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    examined++;
                    lastSequence = rows.getLong("event_sequence");
                    String payload = rows.getString("compact_payload");
                    if (payload != null) {
                        receipts.add(new Receipt(rows.getString("operation_id"),
                                lastSequence, SqliteCheckpointReceipt.create(payload)));
                    }
                }
            }
        }
        return new Batch(examined < BATCH_SIZE ? 0 : lastSequence, List.copyOf(receipts));
    }

    /** Caller owns the transaction: receipt replacement and event removal commit together. */
    void compact(Batch batch) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE operation_envelope SET payload_json = ?
                WHERE operation_id = ? AND phase = 'PUBLISHED'
                """);
             PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM projection_outbox WHERE event_sequence = ? AND operation_id = ?
                """)) {
            for (Receipt receipt : batch.receipts()) {
                update.setString(1, receipt.payload());
                update.setString(2, receipt.operationId());
                if (update.executeUpdate() != 1) {
                    throw new SQLException("checkpoint_compaction_operation_changed");
                }
                delete.setLong(1, receipt.sequence());
                delete.setString(2, receipt.operationId());
                if (delete.executeUpdate() != 1) {
                    throw new SQLException("checkpoint_compaction_event_changed");
                }
            }
        }
    }

    /** Exact receipt and event readback after an uncertain maintenance commit. */
    boolean matches(Batch batch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM operation_envelope o
                WHERE o.operation_id = ? AND o.phase = 'PUBLISHED' AND o.payload_json = ?
                    AND NOT EXISTS (SELECT 1 FROM projection_outbox e WHERE e.operation_id = o.operation_id)
                """)) {
            for (Receipt receipt : batch.receipts()) {
                statement.setString(1, receipt.operationId());
                statement.setString(2, receipt.payload());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    record Receipt(String operationId, long sequence, String payload) { }
    record Batch(long nextSequence, List<Receipt> receipts) { }
}
