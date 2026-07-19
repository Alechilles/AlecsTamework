package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/** SQLite access for active quarantine denial fences. */
public final class PersistenceQuarantineRepository {
    private final SqliteConnectionManager connections;

    public PersistenceQuarantineRepository(@Nonnull SqliteConnectionManager connections) {
        this.connections = connections;
    }

    void insertActive(@Nonnull Connection connection, @Nonnull PersistenceQuarantineRecord record) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_quarantines (
                    quarantine_id, incident_id, scope_type, scope_key, domain, reason_code,
                    state, evidence_hash, generation, created_at_ms, updated_at_ms,
                    cleared_at_ms, clear_verifier
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)
                ON CONFLICT(scope_type, scope_key) WHERE state IN ('ACTIVE', 'VERIFYING')
                DO UPDATE SET
                    incident_id = excluded.incident_id,
                    domain = excluded.domain,
                    reason_code = excluded.reason_code,
                    state = 'ACTIVE',
                    evidence_hash = excluded.evidence_hash,
                    generation = persistence_quarantines.generation + 1,
                    updated_at_ms = excluded.updated_at_ms
                """)) {
            statement.setString(1, record.quarantineId());
            statement.setString(2, record.incidentId());
            statement.setString(3, record.scope().type().name());
            statement.setString(4, record.scope().key());
            statement.setString(5, record.domain().name());
            statement.setString(6, record.reasonCode());
            statement.setString(7, record.state().name());
            statement.setString(8, record.evidenceHash());
            statement.setLong(9, record.generation());
            statement.setLong(10, record.createdAtMs());
            statement.setLong(11, record.updatedAtMs());
            statement.executeUpdate();
        }
    }

    @Nonnull
    public List<PersistenceQuarantineRecord> listActive() throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                    SELECT q.*, s.scope_hash, s.authority_dimension
                    FROM persistence_quarantines q
                    JOIN persistence_incident_scopes s
                      ON s.incident_id = q.incident_id
                     AND s.scope_type = q.scope_type
                     AND s.scope_key = q.scope_key
                    WHERE q.state IN ('ACTIVE', 'VERIFYING')
                    ORDER BY q.updated_at_ms ASC
                    """ );
             ResultSet result = statement.executeQuery()) {
            List<PersistenceQuarantineRecord> records = new ArrayList<>();
            while (result.next()) records.add(mapRecord(result));
            return List.copyOf(records);
        }
    }

    @Nonnull
    public List<PersistenceQuarantineRecord> listActiveForIncident(@Nonnull String incidentId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                    SELECT q.*, s.scope_hash, s.authority_dimension
                    FROM persistence_quarantines q
                    JOIN persistence_incident_scopes s
                      ON s.incident_id = q.incident_id
                     AND s.scope_type = q.scope_type
                     AND s.scope_key = q.scope_key
                    WHERE q.incident_id = ? AND q.state IN ('ACTIVE', 'VERIFYING')
                    ORDER BY q.updated_at_ms ASC
                    """)) {
            statement.setString(1, incidentId);
            try (ResultSet result = statement.executeQuery()) {
                List<PersistenceQuarantineRecord> records = new ArrayList<>();
                while (result.next()) records.add(mapRecord(result));
                return List.copyOf(records);
            }
        }
    }

    public void markVerifying(@Nonnull Connection connection,
                              @Nonnull List<PersistenceQuarantineRecord> records,
                              long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE persistence_quarantines SET state = 'VERIFYING', updated_at_ms = ?
                WHERE quarantine_id = ? AND generation = ? AND evidence_hash = ?
                  AND state IN ('ACTIVE', 'VERIFYING')
                """)) {
            for (PersistenceQuarantineRecord record : records) {
                statement.setLong(1, nowMs);
                statement.setString(2, record.quarantineId());
                statement.setLong(3, record.generation());
                statement.setString(4, record.evidenceHash());
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            for (int result : results) {
                if (result != 1) throw new IllegalStateException("quarantine_generation_changed");
            }
        }
    }

    public void retainActive(@Nonnull Connection connection,
                             @Nonnull String incidentId,
                             long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE persistence_quarantines SET state = 'ACTIVE', updated_at_ms = ?
                WHERE incident_id = ? AND state = 'VERIFYING'
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, incidentId);
            statement.executeUpdate();
        }
    }

    public void clearVerified(@Nonnull Connection connection,
                              @Nonnull List<PersistenceQuarantineRecord> records,
                              @Nonnull java.util.Map<String, String> evidenceHashes,
                              @Nonnull String verifierId,
                              long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE persistence_quarantines
                SET state = 'CLEARED', cleared_at_ms = ?, updated_at_ms = ?, clear_verifier = ?
                WHERE quarantine_id = ? AND generation = ? AND evidence_hash = ?
                  AND state = 'VERIFYING'
                """)) {
            for (PersistenceQuarantineRecord record : records) {
                String verifiedHash = evidenceHashes.get(record.quarantineId());
                if (!record.evidenceHash().equals(verifiedHash)) {
                    throw new IllegalStateException("quarantine_evidence_changed");
                }
                statement.setLong(1, nowMs);
                statement.setLong(2, nowMs);
                statement.setString(3, verifierId);
                statement.setString(4, record.quarantineId());
                statement.setLong(5, record.generation());
                statement.setString(6, verifiedHash);
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            for (int result : results) {
                if (result != 1) throw new IllegalStateException("quarantine_clear_conflict");
            }
        }
    }

    private PersistenceQuarantineRecord mapRecord(ResultSet result) throws Exception {
        PersistenceScope scope = new PersistenceScope(
                PersistenceScopeType.valueOf(result.getString("scope_type")),
                result.getString("scope_key"), result.getString("scope_hash"),
                result.getString("authority_dimension"));
        return new PersistenceQuarantineRecord(
                result.getString("quarantine_id"), result.getString("incident_id"), scope,
                PersistenceDomain.valueOf(result.getString("domain")), result.getString("reason_code"),
                PersistenceQuarantineState.valueOf(result.getString("state")),
                result.getString("evidence_hash"), result.getLong("generation"),
                result.getLong("created_at_ms"), result.getLong("updated_at_ms"),
                result.getLong("cleared_at_ms"), result.getString("clear_verifier")
        );
    }
}
