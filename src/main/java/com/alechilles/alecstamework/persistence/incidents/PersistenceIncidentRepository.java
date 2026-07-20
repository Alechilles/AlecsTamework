package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** SQLite access for bounded incident reads and atomic open/repeat writes. */
public final class PersistenceIncidentRepository {
    private static final int EQUIVALENT_SEARCH_LIMIT = 100;
    private final SqliteConnectionManager connections;

    public PersistenceIncidentRepository(@Nonnull SqliteConnectionManager connections) {
        this.connections = connections;
    }

    @Nonnull
    public Optional<String> findEquivalentOpenIncidentId(@Nonnull String fingerprint,
                                                          @Nonnull List<PersistenceScope> scopes) throws Exception {
        try (Connection connection = connections.openConnection()) {
            for (String incidentId : candidateIncidentIds(connection, fingerprint)) {
                if (scopeKeys(connection, incidentId).equals(scopeKeys(scopes))) {
                    return Optional.of(incidentId);
                }
            }
        }
        return Optional.empty();
    }

    void upsertOpen(@Nonnull Connection connection, @Nonnull PersistenceIncident incident,
                    @Nonnull List<PersistenceScope> scopes) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_incidents (
                    incident_id, fingerprint, status, severity, failure_class, disposition,
                    domain, phase, reason_code, operation_id, boot_id, opened_at_ms, last_seen_at_ms,
                    resolved_at_ms, occurrence_count, recovery_attempts, last_error_type,
                    last_error_message, evidence_json, resolution_code, telemetry_correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?, ?, ?, ?, NULL, ?)
                ON CONFLICT(incident_id) DO UPDATE SET
                    status = 'OPEN',
                    severity = excluded.severity,
                    failure_class = excluded.failure_class,
                    disposition = excluded.disposition,
                    domain = excluded.domain,
                    phase = excluded.phase,
                    reason_code = excluded.reason_code,
                    operation_id = COALESCE(excluded.operation_id, persistence_incidents.operation_id),
                    last_seen_at_ms = excluded.last_seen_at_ms,
                    occurrence_count = persistence_incidents.occurrence_count + 1,
                    last_error_type = excluded.last_error_type,
                    last_error_message = excluded.last_error_message,
                    evidence_json = excluded.evidence_json,
                    telemetry_correlation_id = COALESCE(excluded.telemetry_correlation_id,
                        persistence_incidents.telemetry_correlation_id)
                """)) {
            bindIncident(statement, incident);
            statement.executeUpdate();
        }
        for (PersistenceScope scope : scopes) insertScope(connection, incident, scope);
    }

    @Nonnull
    public List<PersistenceIncident> listOpen(int requestedLimit) throws Exception {
        return listRecent(true, requestedLimit);
    }

    @Nonnull
    public List<PersistenceIncident> listRecent(boolean openOnly, int requestedLimit) throws Exception {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        String sql = openOnly
                ? """
                  SELECT * FROM persistence_incidents
                  WHERE status IN ('OPEN', 'RECOVERING')
                  ORDER BY last_seen_at_ms DESC LIMIT ?
                  """
                : """
                  SELECT * FROM persistence_incidents
                  ORDER BY last_seen_at_ms DESC LIMIT ?
                  """;
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<PersistenceIncident> incidents = new ArrayList<>();
                while (result.next()) incidents.add(mapIncident(result));
                return List.copyOf(incidents);
            }
        }
    }

    @Nonnull
    public Optional<PersistenceIncident> findById(@Nonnull String incidentId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM persistence_incidents WHERE incident_id = ?")) {
            statement.setString(1, incidentId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapIncident(result)) : Optional.empty();
            }
        }
    }

    @Nonnull
    public Optional<PersistenceIncident> findByIdOrUniquePrefix(@Nonnull String incidentId) throws Exception {
        String normalized = incidentId.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[0-9a-f-]{4,64}")) return Optional.empty();
        Optional<PersistenceIncident> exact = findById(normalized);
        if (exact.isPresent()) return exact;
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM persistence_incidents
                     WHERE lower(incident_id) LIKE ?
                     ORDER BY last_seen_at_ms DESC LIMIT 2
                     """)) {
            statement.setString(1, normalized + "%");
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                PersistenceIncident match = mapIncident(result);
                return result.next() ? Optional.empty() : Optional.of(match);
            }
        }
    }

    @Nonnull
    public List<PersistenceScope> listScopes(@Nonnull String incidentId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT scope_type, scope_key, scope_hash, authority_dimension
                     FROM persistence_incident_scopes WHERE incident_id = ?
                     ORDER BY scope_type, scope_hash
                     """)) {
            statement.setString(1, incidentId);
            try (ResultSet result = statement.executeQuery()) {
                List<PersistenceScope> scopes = new ArrayList<>();
                while (result.next()) {
                    scopes.add(new PersistenceScope(
                            PersistenceScopeType.valueOf(result.getString("scope_type")),
                            result.getString("scope_key"), result.getString("scope_hash"),
                            result.getString("authority_dimension")));
                }
                return List.copyOf(scopes);
            }
        }
    }

    public boolean beginRecovery(@Nonnull Connection connection,
                                 @Nonnull String incidentId,
                                 long expectedAttempts,
                                 long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE persistence_incidents
                SET status = 'RECOVERING', recovery_attempts = recovery_attempts + 1,
                    last_seen_at_ms = ?
                WHERE incident_id = ? AND status IN ('OPEN', 'RECOVERING')
                  AND recovery_attempts = ?
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, incidentId);
            statement.setLong(3, expectedAttempts);
            return statement.executeUpdate() == 1;
        }
    }

    public void retainOpen(@Nonnull Connection connection,
                           @Nonnull String incidentId,
                           @Nonnull String reason,
                           @Nullable Throwable failure,
                           long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE persistence_incidents
                SET status = 'OPEN', last_seen_at_ms = ?, resolution_code = ?,
                    last_error_type = ?, last_error_message = ?
                WHERE incident_id = ? AND status = 'RECOVERING'
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, reason);
            statement.setString(3, failure == null ? null : failure.getClass().getName());
            statement.setString(4, failure == null ? null : bounded(failure.getMessage(), 1_000));
            statement.setString(5, incidentId);
            statement.executeUpdate();
        }
    }

    public void resolve(@Nonnull Connection connection,
                        @Nonnull String incidentId,
                        @Nonnull String resolutionCode,
                        long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE persistence_incidents
                SET status = 'RESOLVED', resolved_at_ms = ?, last_seen_at_ms = ?,
                    resolution_code = ?, last_error_type = NULL, last_error_message = NULL
                WHERE incident_id = ? AND status = 'RECOVERING'
                """)) {
            statement.setLong(1, nowMs);
            statement.setLong(2, nowMs);
            statement.setString(3, resolutionCode);
            statement.setString(4, incidentId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("incident_recovery_state_changed");
            }
        }
    }

    private List<String> candidateIncidentIds(Connection connection, String fingerprint) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT incident_id FROM persistence_incidents
                WHERE fingerprint = ? AND status IN ('OPEN', 'RECOVERING')
                ORDER BY last_seen_at_ms DESC LIMIT ?
                """)) {
            statement.setString(1, fingerprint);
            statement.setInt(2, EQUIVALENT_SEARCH_LIMIT);
            try (ResultSet result = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (result.next()) ids.add(result.getString(1));
                return ids;
            }
        }
    }

    private Set<PersistenceScope.ScopeKey> scopeKeys(Connection connection, String incidentId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT scope_type, scope_key FROM persistence_incident_scopes WHERE incident_id = ?
                """)) {
            statement.setString(1, incidentId);
            try (ResultSet result = statement.executeQuery()) {
                Set<PersistenceScope.ScopeKey> keys = new HashSet<>();
                while (result.next()) keys.add(new PersistenceScope.ScopeKey(
                        PersistenceScopeType.valueOf(result.getString(1)), result.getString(2)));
                return Set.copyOf(keys);
            }
        }
    }

    private Set<PersistenceScope.ScopeKey> scopeKeys(List<PersistenceScope> scopes) {
        Set<PersistenceScope.ScopeKey> keys = new HashSet<>();
        for (PersistenceScope scope : scopes) keys.add(scope.lookupKey());
        return Set.copyOf(keys);
    }

    private void insertScope(Connection connection, PersistenceIncident incident,
                             PersistenceScope scope) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO persistence_incident_scopes (
                    incident_id, scope_type, scope_key, scope_hash, authority_dimension, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, incident.incidentId());
            statement.setString(2, scope.type().name());
            statement.setString(3, scope.key());
            statement.setString(4, scope.scopeHash());
            statement.setString(5, scope.authorityDimension());
            statement.setLong(6, incident.openedAtMs());
            statement.executeUpdate();
        }
    }

    private void bindIncident(PreparedStatement statement, PersistenceIncident incident) throws Exception {
        statement.setString(1, incident.incidentId());
        statement.setString(2, incident.fingerprint());
        statement.setString(3, incident.status().name());
        statement.setString(4, incident.severity().name());
        statement.setString(5, incident.failureClass().name());
        statement.setString(6, incident.disposition().name());
        statement.setString(7, incident.domain().name());
        statement.setString(8, incident.phase().name());
        statement.setString(9, incident.reasonCode());
        statement.setString(10, incident.operationId());
        statement.setString(11, incident.bootId());
        statement.setLong(12, incident.openedAtMs());
        statement.setLong(13, incident.lastSeenAtMs());
        statement.setLong(14, incident.recoveryAttempts());
        statement.setString(15, incident.lastErrorType());
        statement.setString(16, incident.lastErrorMessage());
        statement.setString(17, incident.evidenceJson());
        statement.setString(18, incident.telemetryCorrelationId());
    }

    private PersistenceIncident mapIncident(ResultSet result) throws Exception {
        return new PersistenceIncident(
                result.getString("incident_id"), result.getString("fingerprint"),
                PersistenceIncidentStatus.valueOf(result.getString("status")),
                PersistenceIncidentSeverity.valueOf(result.getString("severity")),
                PersistenceFailureClass.valueOf(result.getString("failure_class")),
                PersistenceDisposition.valueOf(result.getString("disposition")),
                PersistenceDomain.valueOf(result.getString("domain")),
                PersistenceOperationPhase.valueOf(result.getString("phase")),
                result.getString("reason_code"), result.getString("operation_id"), result.getString("boot_id"),
                result.getLong("opened_at_ms"), result.getLong("last_seen_at_ms"),
                result.getLong("resolved_at_ms"), result.getLong("occurrence_count"),
                result.getLong("recovery_attempts"), result.getString("last_error_type"),
                result.getString("last_error_message"), result.getString("evidence_json"),
                result.getString("resolution_code"), result.getString("telemetry_correlation_id")
        );
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), maxLength));
    }
}
