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
