package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.Statement;
import javax.annotation.Nonnull;

/** Adds persistence incident, quarantine, circuit, and storage-probe state without rewriting v6 authority. */
final class SqliteSchemaV7Migration {

    void apply(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            createIncidents(statement);
            createIncidentScopes(statement);
            createQuarantines(statement);
            createFeatureCircuits(statement);
            createStorageProbe(statement);
        }
    }

    private void createIncidents(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS persistence_incidents (
                    incident_id TEXT PRIMARY KEY,
                    fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL CHECK (status IN ('OPEN', 'RECOVERING', 'RESOLVED', 'SUPERSEDED')),
                    severity TEXT NOT NULL,
                    failure_class TEXT NOT NULL,
                    disposition TEXT NOT NULL,
                    domain TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    reason_code TEXT NOT NULL,
                    operation_id TEXT,
                    boot_id TEXT NOT NULL,
                    opened_at_ms INTEGER NOT NULL,
                    last_seen_at_ms INTEGER NOT NULL,
                    resolved_at_ms INTEGER,
                    occurrence_count INTEGER NOT NULL CHECK (occurrence_count > 0),
                    recovery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (recovery_attempts >= 0),
                    last_error_type TEXT,
                    last_error_message TEXT,
                    evidence_json TEXT NOT NULL,
                    resolution_code TEXT,
                    telemetry_correlation_id TEXT
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_incidents_status_seen
                ON persistence_incidents(status, last_seen_at_ms)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_incidents_fingerprint_status
                ON persistence_incidents(fingerprint, status)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_incidents_operation
                ON persistence_incidents(operation_id)
                WHERE operation_id IS NOT NULL
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_incidents_domain_reason
                ON persistence_incidents(domain, reason_code)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_incidents_telemetry_correlation
                ON persistence_incidents(telemetry_correlation_id)
                WHERE telemetry_correlation_id IS NOT NULL
                """);
    }

    private void createIncidentScopes(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS persistence_incident_scopes (
                    incident_id TEXT NOT NULL,
                    scope_type TEXT NOT NULL,
                    scope_key TEXT NOT NULL,
                    scope_hash TEXT NOT NULL,
                    authority_dimension TEXT,
                    created_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (incident_id, scope_type, scope_key),
                    FOREIGN KEY (incident_id) REFERENCES persistence_incidents(incident_id) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_incident_scopes_key
                ON persistence_incident_scopes(scope_type, scope_key)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_incident_scopes_hash
                ON persistence_incident_scopes(scope_type, scope_hash)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_incident_scopes_incident
                ON persistence_incident_scopes(incident_id)
                """);
    }

    private void createQuarantines(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS persistence_quarantines (
                    quarantine_id TEXT PRIMARY KEY,
                    incident_id TEXT NOT NULL,
                    scope_type TEXT NOT NULL,
                    scope_key TEXT NOT NULL,
                    domain TEXT NOT NULL,
                    reason_code TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'VERIFYING', 'CLEARED')),
                    evidence_hash TEXT NOT NULL,
                    generation INTEGER NOT NULL CHECK (generation >= 0),
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    cleared_at_ms INTEGER,
                    clear_verifier TEXT,
                    FOREIGN KEY (incident_id) REFERENCES persistence_incidents(incident_id) ON DELETE RESTRICT
                )
                """);
        statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_persistence_quarantine_active_scope
                ON persistence_quarantines(scope_type, scope_key)
                WHERE state IN ('ACTIVE', 'VERIFYING')
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_quarantines_incident
                ON persistence_quarantines(incident_id)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_quarantines_domain_state
                ON persistence_quarantines(domain, state, updated_at_ms)
                """);
    }

    private void createFeatureCircuits(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS persistence_feature_circuits (
                    domain TEXT PRIMARY KEY,
                    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
                    reason_code TEXT,
                    updated_at_ms INTEGER NOT NULL,
                    updated_by TEXT
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS persistence_feature_circuit_audit (
                    sequence_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_id TEXT NOT NULL UNIQUE,
                    domain TEXT NOT NULL,
                    previous_enabled INTEGER CHECK (previous_enabled IN (0, 1)),
                    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
                    reason_code TEXT,
                    changed_at_ms INTEGER NOT NULL,
                    changed_by TEXT
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_persistence_feature_circuit_audit_time
                ON persistence_feature_circuit_audit(changed_at_ms DESC, sequence_id DESC)
                """);
    }

    private void createStorageProbe(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS persistence_storage_probe (
                    probe_id INTEGER PRIMARY KEY CHECK (probe_id = 1),
                    revision INTEGER NOT NULL CHECK (revision >= 0),
                    updated_at_ms INTEGER NOT NULL,
                    last_boot_id TEXT NOT NULL
                )
                """);
        statement.execute("""
                INSERT OR IGNORE INTO persistence_storage_probe (
                    probe_id, revision, updated_at_ms, last_boot_id
                ) VALUES (1, 0, 0, 'schema-v7')
                """);
    }
}
