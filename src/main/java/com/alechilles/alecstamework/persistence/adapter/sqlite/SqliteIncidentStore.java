package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.incidents.IncidentRecord;
import com.alechilles.alecstamework.persistence.incidents.IncidentState;
import com.alechilles.alecstamework.persistence.incidents.IncidentStore;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Connection-bound SQLite adapter for replacement incidents and exact scoped containment.
 */
public final class SqliteIncidentStore implements IncidentStore {
    private final Connection connection;

    public SqliteIncidentStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Incident store connection is required");
        }
        this.connection = connection;
    }

    @Override
    public Optional<IncidentRecord> findIncident(IncidentId incidentId) {
        require(incidentId, "Incident ID");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT incident_id, failure_kind, failure_code, state, summary,
                       evidence_json, created_at_ms, resolved_at_ms
                FROM persistence_incident
                WHERE incident_id = ?
                """)) {
            statement.setString(1, incidentId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readIncident(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("incident_find", failure);
        }
    }

    @Override
    public PersistenceMutationResult<IncidentRecord> createIncident(IncidentRecord incident) {
        require(incident, "Incident");
        Optional<IncidentRecord> existing = findIncident(incident.incidentId());
        if (existing.isPresent()) {
            return existing.get().equals(incident)
                    ? PersistenceMutationResult.applied(existing.get())
                    : PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_incident(
                    incident_id, failure_kind, failure_code, state, summary,
                    evidence_json, created_at_ms, resolved_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, incident.incidentId().toString());
            statement.setString(2, incident.failureKind());
            statement.setString(3, incident.failureCode());
            statement.setString(4, incident.state().name());
            statement.setString(5, incident.summary());
            statement.setString(6, incident.evidenceJson());
            statement.setLong(7, incident.createdAtMs());
            setNullableLong(statement, 8, incident.resolvedAtMs());
            statement.executeUpdate();
            return PersistenceMutationResult.applied(incident);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("incident_create", failure);
        }
    }

    @Override
    public PersistenceMutationResult<IncidentRecord> resolveIncident(
            IncidentId incidentId,
            long resolvedAtMs
    ) {
        require(incidentId, "Incident ID");
        IncidentRecord current = findIncident(incidentId).orElse(null);
        if (current == null) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (current.state() == IncidentState.RESOLVED) {
            return PersistenceMutationResult.applied(current);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE persistence_incident
                SET state = 'RESOLVED', resolved_at_ms = ?
                WHERE incident_id = ? AND state = 'OPEN'
                """)) {
            statement.setLong(1, resolvedAtMs);
            statement.setString(2, incidentId.toString());
            if (statement.executeUpdate() != 1) {
                return PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
            }
            return PersistenceMutationResult.applied(new IncidentRecord(
                    current.incidentId(), current.failureKind(), current.failureCode(),
                    IncidentState.RESOLVED, current.summary(), current.evidenceJson(),
                    current.createdAtMs(), resolvedAtMs
            ));
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("incident_resolve", failure);
        }
    }

    @Override
    public Optional<ScopeQuarantine> findQuarantine(OperationScope scope) {
        require(scope, "Quarantine scope");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT scope_type, scope_key, incident_id, state, reason_code,
                       created_at_ms, released_at_ms
                FROM persistence_quarantine
                WHERE scope_type = ? AND scope_key = ?
                """)) {
            statement.setString(1, scope.type().name());
            statement.setString(2, scope.key());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readQuarantine(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("quarantine_find", failure);
        }
    }

    @Override
    public List<ScopeQuarantine> findActiveQuarantines(List<OperationScope> candidateScopes) {
        if (candidateScopes == null) {
            throw new IllegalArgumentException("Candidate quarantine scopes are required");
        }
        ArrayList<ScopeQuarantine> active = new ArrayList<>();
        java.util.TreeSet<OperationScope> distinct = new java.util.TreeSet<>(candidateScopes);
        for (OperationScope scope : distinct) {
            findQuarantine(scope)
                    .filter(row -> row.state() == QuarantineState.ACTIVE)
                    .ifPresent(active::add);
        }
        return List.copyOf(active);
    }

    @Override
    public PersistenceMutationResult<ScopeQuarantine> quarantine(
            ScopeQuarantine quarantine
    ) {
        require(quarantine, "Quarantine");
        if (quarantine.state() != QuarantineState.ACTIVE) {
            throw new IllegalArgumentException("New quarantine must be active");
        }
        IncidentRecord incident = findIncident(quarantine.incidentId()).orElse(null);
        if (incident == null || incident.state() != IncidentState.OPEN) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        Optional<ScopeQuarantine> existing = findQuarantine(quarantine.scope());
        if (existing.isPresent()) {
            return existing.get().equals(quarantine)
                    ? PersistenceMutationResult.applied(existing.get())
                    : PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_quarantine(
                    scope_type, scope_key, incident_id, state, reason_code,
                    created_at_ms, released_at_ms
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, NULL)
                """)) {
            statement.setString(1, quarantine.scope().type().name());
            statement.setString(2, quarantine.scope().key());
            statement.setString(3, quarantine.incidentId().toString());
            statement.setString(4, quarantine.reasonCode());
            statement.setLong(5, quarantine.createdAtMs());
            statement.executeUpdate();
            return PersistenceMutationResult.applied(quarantine);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("quarantine_create", failure);
        }
    }

    @Override
    public PersistenceMutationResult<ScopeQuarantine> release(
            OperationScope scope,
            IncidentId expectedIncidentId,
            long releasedAtMs
    ) {
        require(scope, "Quarantine scope");
        require(expectedIncidentId, "Expected incident ID");
        ScopeQuarantine current = findQuarantine(scope).orElse(null);
        if (current == null) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (!current.incidentId().equals(expectedIncidentId)) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        if (current.state() == QuarantineState.RELEASED) {
            return PersistenceMutationResult.applied(current);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE persistence_quarantine
                SET state = 'RELEASED', released_at_ms = ?
                WHERE scope_type = ? AND scope_key = ?
                  AND incident_id = ? AND state = 'ACTIVE'
                """)) {
            statement.setLong(1, releasedAtMs);
            statement.setString(2, scope.type().name());
            statement.setString(3, scope.key());
            statement.setString(4, expectedIncidentId.toString());
            if (statement.executeUpdate() != 1) {
                return PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
            }
            return PersistenceMutationResult.applied(new ScopeQuarantine(
                    scope, expectedIncidentId, QuarantineState.RELEASED,
                    current.reasonCode(), current.createdAtMs(), releasedAtMs
            ));
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("quarantine_release", failure);
        }
    }

    private IncidentRecord readIncident(ResultSet row) throws SQLException {
        return new IncidentRecord(
                IncidentId.parse(row.getString("incident_id")),
                row.getString("failure_kind"),
                row.getString("failure_code"),
                IncidentState.valueOf(row.getString("state")),
                row.getString("summary"),
                row.getString("evidence_json"),
                row.getLong("created_at_ms"),
                nullableLong(row, "resolved_at_ms")
        );
    }

    private ScopeQuarantine readQuarantine(ResultSet row) throws SQLException {
        return new ScopeQuarantine(
                new OperationScope(
                        OperationScopeType.valueOf(row.getString("scope_type")),
                        row.getString("scope_key")
                ),
                IncidentId.parse(row.getString("incident_id")),
                QuarantineState.valueOf(row.getString("state")),
                row.getString("reason_code"),
                row.getLong("created_at_ms"),
                nullableLong(row, "released_at_ms")
        );
    }

    private Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private PersistenceStoreException storeFailure(String operation, Throwable failure) {
        if (failure instanceof PersistenceStoreException storeException) {
            return storeException;
        }
        return new PersistenceStoreException(operation, failure);
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
