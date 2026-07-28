package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEvidence;
import com.google.gson.Gson;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Connection-local persistence for profile-lifetime capture-source authority. */
final class SqliteBondedCompanionCaptureSourceStore {
    private static final Gson GSON = new Gson();
    private final Connection connection;

    SqliteBondedCompanionCaptureSourceStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    void insert(SqliteBondedCompanionCaptureSourceRow row) throws SQLException {
        Objects.requireNonNull(row, "row");
        BondedCompanionCaptureEvidence evidence = row.evidence();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bonded_companion_capture_source(
                    profile_id, owner_uuid, roster_id, source_npc_uuid,
                    source_world_key, caller_namespace, idempotency_key,
                    request_hash, capture_evidence_json,
                    capture_snapshot_json, committed_at_ms,
                    event_published_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, evidence.profileId());
            statement.setString(2, evidence.ownerUuid().toString());
            statement.setString(3, evidence.rosterId());
            statement.setString(4, evidence.sourceNpcUuid().toString());
            statement.setString(5, evidence.sourceWorldKey());
            statement.setString(6, evidence.callerNamespace());
            statement.setString(7, evidence.idempotencyKey());
            statement.setString(8, row.requestHash());
            statement.setString(9, GSON.toJson(evidence));
            statement.setString(10, GSON.toJson(row.capturedProfile()));
            statement.setLong(11, evidence.committedAtMs());
            if (row.eventPublishedAtMs() == null) statement.setNull(12,
                    java.sql.Types.BIGINT);
            else statement.setLong(12, row.eventPublishedAtMs());
            statement.executeUpdate();
        }
    }

    Optional<SqliteBondedCompanionCaptureSourceRow> find(
            UUID ownerUuid, String rosterId, UUID sourceNpcUuid
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM bonded_companion_capture_source
                WHERE owner_uuid = ? AND roster_id = ? AND source_npc_uuid = ?
                """)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, rosterId);
            statement.setString(3, sourceNpcUuid.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    List<SqliteBondedCompanionCaptureSourceRow> findBySource(
            UUID sourceNpcUuid, int limit
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM bonded_companion_capture_source
                WHERE source_npc_uuid = ? ORDER BY committed_at_ms, profile_id
                LIMIT ?
                """)) {
            statement.setString(1, sourceNpcUuid.toString());
            statement.setInt(2, limit);
            return rows(statement);
        }
    }

    List<SqliteBondedCompanionCaptureSourceRow> unpublished(int limit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM bonded_companion_capture_source
                WHERE event_published_at_ms IS NULL
                ORDER BY committed_at_ms, profile_id LIMIT ?
                """)) {
            statement.setInt(1, limit);
            return rows(statement);
        }
    }

    boolean markPublished(BondedCompanionCaptureEvidence evidence, long atMs)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_companion_capture_source
                SET event_published_at_ms = ?
                WHERE caller_namespace = ? AND idempotency_key = ?
                  AND profile_id = ? AND event_published_at_ms IS NULL
                """)) {
            statement.setLong(1, atMs);
            statement.setString(2, evidence.callerNamespace());
            statement.setString(3, evidence.idempotencyKey());
            statement.setString(4, evidence.profileId());
            return statement.executeUpdate() == 1;
        }
    }

    private List<SqliteBondedCompanionCaptureSourceRow> rows(
            PreparedStatement statement
    ) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            ArrayList<SqliteBondedCompanionCaptureSourceRow> result =
                    new ArrayList<>();
            while (rows.next()) result.add(read(rows));
            return List.copyOf(result);
        }
    }

    private SqliteBondedCompanionCaptureSourceRow read(ResultSet row)
            throws SQLException {
        BondedCompanionCaptureEvidence evidence = GSON.fromJson(
                row.getString("capture_evidence_json"),
                BondedCompanionCaptureEvidence.class);
        SqliteBondedCompanionProfileRow profile = GSON.fromJson(
                row.getString("capture_snapshot_json"),
                SqliteBondedCompanionProfileRow.class);
        long published = row.getLong("event_published_at_ms");
        Long publishedAt = row.wasNull() ? null : published;
        return new SqliteBondedCompanionCaptureSourceRow(
                evidence, profile, row.getString("request_hash"), publishedAt);
    }
}
