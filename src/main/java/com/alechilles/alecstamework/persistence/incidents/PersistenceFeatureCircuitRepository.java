package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Persists local operator circuit overrides independently from companion authority rows. */
public final class PersistenceFeatureCircuitRepository {
    private final SqliteConnectionManager connections;
    private final PersistenceWriteQueue writeQueue;

    public PersistenceFeatureCircuitRepository(@Nonnull SqliteConnectionManager connections,
                                               @Nonnull PersistenceWriteQueue writeQueue) {
        this.connections = connections;
        this.writeQueue = writeQueue;
    }

    @Nonnull
    public Map<PersistenceDomain, PersistenceFeatureCircuitRegistry.CircuitState> load() throws Exception {
        EnumMap<PersistenceDomain, PersistenceFeatureCircuitRegistry.CircuitState> states =
                new EnumMap<>(PersistenceDomain.class);
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT domain, enabled, reason_code, updated_at_ms, updated_by
                     FROM persistence_feature_circuits
                     """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                PersistenceDomain domain = parseDomain(result.getString("domain"));
                if (domain == null) continue;
                states.put(domain, new PersistenceFeatureCircuitRegistry.CircuitState(
                        result.getInt("enabled") != 0,
                        result.getString("reason_code"),
                        result.getLong("updated_at_ms"),
                        result.getString("updated_by")
                ));
            }
        }
        return Map.copyOf(states);
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Void> set(
            @Nonnull PersistenceDomain domain,
            boolean enabled,
            @Nullable String reasonCode,
            long updatedAtMs,
            @Nullable String updatedBy,
            @Nonnull PersistenceFeatureCircuitRegistry registry) {
        return writeQueue.submitTracked("persistence_feature_circuit_set", connection -> {
            Boolean previousEnabled = loadEnabled(connection, domain);
            if (previousEnabled == null) previousEnabled = true;
            upsert(connection, domain, enabled, reasonCode, updatedAtMs, updatedBy);
            insertAudit(connection, domain, previousEnabled, enabled, reasonCode, updatedAtMs, updatedBy);
            return null;
        }, ignored -> registry.publish(domain, enabled, reasonCode, updatedAtMs, updatedBy));
    }

    @Nonnull
    public List<CircuitAuditRecord> listRecentAudit(int requestedLimit) throws Exception {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        ArrayList<CircuitAuditRecord> records = new ArrayList<>();
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT event_id, domain, previous_enabled, enabled, reason_code,
                            changed_at_ms, changed_by
                     FROM persistence_feature_circuit_audit
                     ORDER BY changed_at_ms DESC, sequence_id DESC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    PersistenceDomain domain = parseDomain(result.getString("domain"));
                    if (domain == null) continue;
                    records.add(new CircuitAuditRecord(
                            result.getString("event_id"), domain,
                            nullableBoolean(result, "previous_enabled"),
                            result.getInt("enabled") != 0,
                            result.getString("reason_code"),
                            result.getLong("changed_at_ms"),
                            result.getString("changed_by")));
                }
            }
        }
        return List.copyOf(records);
    }

    private void upsert(Connection connection, PersistenceDomain domain, boolean enabled,
                        String reasonCode, long updatedAtMs, String updatedBy) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_feature_circuits (
                    domain, enabled, reason_code, updated_at_ms, updated_by
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(domain) DO UPDATE SET
                    enabled = excluded.enabled,
                    reason_code = excluded.reason_code,
                    updated_at_ms = excluded.updated_at_ms,
                    updated_by = excluded.updated_by
                """)) {
            statement.setString(1, domain.name());
            statement.setInt(2, enabled ? 1 : 0);
            statement.setString(3, normalize(reasonCode));
            statement.setLong(4, updatedAtMs);
            statement.setString(5, normalize(updatedBy));
            statement.executeUpdate();
        }
    }

    @Nullable
    private Boolean loadEnabled(Connection connection, PersistenceDomain domain) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enabled FROM persistence_feature_circuits WHERE domain = ?")) {
            statement.setString(1, domain.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) != 0 : null;
            }
        }
    }

    private void insertAudit(Connection connection,
                             PersistenceDomain domain,
                             @Nullable Boolean previousEnabled,
                             boolean enabled,
                             @Nullable String reasonCode,
                             long updatedAtMs,
                             @Nullable String updatedBy) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_feature_circuit_audit (
                    event_id, domain, previous_enabled, enabled, reason_code, changed_at_ms, changed_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, domain.name());
            if (previousEnabled == null) statement.setNull(3, java.sql.Types.INTEGER);
            else statement.setInt(3, previousEnabled ? 1 : 0);
            statement.setInt(4, enabled ? 1 : 0);
            statement.setString(5, normalize(reasonCode));
            statement.setLong(6, updatedAtMs);
            statement.setString(7, normalize(updatedBy));
            statement.executeUpdate();
        }
    }

    @Nullable
    private Boolean nullableBoolean(ResultSet result, String column) throws Exception {
        int value = result.getInt(column);
        return result.wasNull() ? null : value != 0;
    }

    @Nullable
    private PersistenceDomain parseDomain(String raw) {
        try {
            return PersistenceDomain.valueOf(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Immutable local audit row; changed-by is already installation-local and sanitized. */
    public record CircuitAuditRecord(@Nonnull String eventId,
                                     @Nonnull PersistenceDomain domain,
                                     @Nullable Boolean previousEnabled,
                                     boolean enabled,
                                     @Nullable String reasonCode,
                                     long changedAtMs,
                                     @Nullable String changedBy) {
    }
}
