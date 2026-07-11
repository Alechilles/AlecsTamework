package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.Statement;
import javax.annotation.Nonnull;

/**
 * Installs the additive schema-v5 import journal used before vanilla residents are neutralized.
 *
 * <p>This schema is reconciled on every v5 startup because early v5 databases predate these
 * tables. The immutable triggers keep the original audit envelope and every source observation
 * available for rollback, diagnostics, and exact replay checks.</p>
 */
final class SqliteManagedCoopImportSchema {
    void ensure(@Nonnull Connection connection) throws Exception {
        createSessions(connection);
        createSources(connection);
        createIndexes(connection);
        createImmutableEvidenceTriggers(connection);
    }

    private void createSessions(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS managed_coop_import_sessions (
                        session_id TEXT PRIMARY KEY,
                        authority_id TEXT NOT NULL,
                        world_name TEXT NOT NULL,
                        coop_id TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        audit_version INTEGER NOT NULL CHECK (audit_version >= 1),
                        audit_fingerprint TEXT NOT NULL,
                        audit_envelope_json TEXT NOT NULL,
                        audit_envelope_hash TEXT NOT NULL,
                        layout_id TEXT NOT NULL,
                        coop_asset_id TEXT,
                        resident_list_class_name TEXT NOT NULL,
                        produce_payload TEXT NOT NULL,
                        produce_fingerprint TEXT NOT NULL,
                        source_count INTEGER NOT NULL CHECK (source_count >= 0),
                        state TEXT NOT NULL CHECK (state IN (
                            'ACTIVE', 'FINALIZED_MANAGED', 'FINALIZED_CONFLICT'
                        )),
                        active INTEGER NOT NULL CHECK (active IN (0, 1)),
                        begin_command_id TEXT NOT NULL,
                        final_command_id TEXT,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        finalized_at_ms INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        FOREIGN KEY (authority_id)
                            REFERENCES managed_coop_authority(authority_id) ON DELETE RESTRICT
                    )
                    """);
        }
    }

    private void createSources(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS managed_coop_import_sources (
                        source_id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        source_fingerprint TEXT NOT NULL,
                        source_envelope_json TEXT NOT NULL,
                        source_envelope_hash TEXT NOT NULL,
                        source_payload TEXT NOT NULL,
                        source_payload_hash TEXT NOT NULL,
                        locator_hints_json TEXT NOT NULL,
                        locator_hints_hash TEXT NOT NULL,
                        source_slot INTEGER NOT NULL CHECK (source_slot >= 0),
                        source_order INTEGER NOT NULL CHECK (source_order >= 0),
                        metadata_present INTEGER NOT NULL CHECK (metadata_present IN (0, 1)),
                        persistent_ref_present INTEGER NOT NULL CHECK (persistent_ref_present IN (0, 1)),
                        persistent_uuid TEXT,
                        deployed_to_world INTEGER NOT NULL CHECK (deployed_to_world IN (0, 1)),
                        last_produced TEXT,
                        profile_at_audit_id TEXT,
                        role_id TEXT,
                        display_name TEXT,
                        managed_snapshot_json TEXT NOT NULL,
                        managed_snapshot_hash TEXT NOT NULL,
                        managed_snapshot_version INTEGER NOT NULL CHECK (managed_snapshot_version >= 1),
                        unavailable_fields_json TEXT NOT NULL,
                        disposition_kind TEXT CHECK (disposition_kind IN (
                            'MATCHED', 'IMPORTED', 'QUARANTINED'
                        )),
                        disposition_command_id TEXT,
                        operation_id TEXT,
                        resident_id TEXT,
                        profile_id TEXT,
                        conflict_id TEXT,
                        conflict_kind TEXT,
                        neutralization_state TEXT NOT NULL CHECK (neutralization_state IN (
                            'NOT_AUTHORIZED', 'AUTHORIZED', 'VERIFIED_ABSENT', 'NOT_REQUIRED'
                        )),
                        neutralization_command_id TEXT,
                        absence_proof_json TEXT,
                        absence_proof_hash TEXT,
                        absence_proof_version INTEGER NOT NULL DEFAULT 0
                            CHECK (absence_proof_version >= 0),
                        created_at_ms INTEGER NOT NULL,
                        disposition_at_ms INTEGER NOT NULL DEFAULT 0,
                        verified_absent_at_ms INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (session_id)
                            REFERENCES managed_coop_import_sessions(session_id) ON DELETE RESTRICT,
                        FOREIGN KEY (operation_id)
                            REFERENCES coop_lifecycle_operations(operation_id) ON DELETE RESTRICT,
                        FOREIGN KEY (resident_id)
                            REFERENCES managed_coop_residents(resident_id) ON DELETE RESTRICT,
                        FOREIGN KEY (profile_id)
                            REFERENCES npc_profiles(profile_id) ON DELETE RESTRICT,
                        FOREIGN KEY (conflict_id)
                            REFERENCES coop_import_conflicts(conflict_id) ON DELETE RESTRICT,
                        CHECK (
                            (persistent_ref_present = 1 AND persistent_uuid IS NOT NULL)
                            OR (persistent_ref_present = 0 AND persistent_uuid IS NULL)
                        ),
                        CHECK (
                            (disposition_kind IS NULL
                                AND disposition_command_id IS NULL
                                AND operation_id IS NULL AND resident_id IS NULL
                                AND profile_id IS NULL AND conflict_id IS NULL
                                AND conflict_kind IS NULL
                                AND neutralization_state = 'NOT_AUTHORIZED'
                                AND disposition_at_ms = 0)
                            OR (disposition_kind IN ('MATCHED', 'IMPORTED')
                                AND disposition_command_id IS NOT NULL
                                AND operation_id IS NOT NULL AND resident_id IS NOT NULL
                                AND profile_id IS NOT NULL AND conflict_id IS NULL
                                AND conflict_kind IS NULL
                                AND neutralization_state IN ('AUTHORIZED', 'VERIFIED_ABSENT')
                                AND disposition_at_ms <> 0)
                            OR (disposition_kind = 'QUARANTINED'
                                AND disposition_command_id IS NOT NULL
                                AND operation_id IS NULL AND resident_id IS NULL
                                AND profile_id IS NULL AND conflict_id IS NOT NULL
                                AND conflict_kind IS NOT NULL
                                AND neutralization_state = 'NOT_REQUIRED'
                                AND disposition_at_ms <> 0)
                        ),
                        CHECK (
                            (neutralization_state = 'VERIFIED_ABSENT'
                                AND neutralization_command_id IS NOT NULL
                                AND absence_proof_json IS NOT NULL
                                AND absence_proof_hash IS NOT NULL
                                AND absence_proof_version >= 1
                                AND verified_absent_at_ms <> 0)
                            OR (neutralization_state <> 'VERIFIED_ABSENT'
                                AND neutralization_command_id IS NULL
                                AND absence_proof_json IS NULL
                                AND absence_proof_hash IS NULL
                                AND absence_proof_version = 0
                                AND verified_absent_at_ms = 0)
                        )
                    )
                    """);
        }
    }

    private void createIndexes(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_coop_import_active_authority ON managed_coop_import_sessions(authority_id) WHERE active = 1");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_managed_coop_import_session_location ON managed_coop_import_sessions(world_name, x, y, z, coop_id)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_coop_import_source_fingerprint ON managed_coop_import_sources(session_id, source_fingerprint)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_coop_import_source_slot ON managed_coop_import_sources(session_id, source_slot)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_coop_import_source_order ON managed_coop_import_sources(session_id, source_order)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_managed_coop_import_source_disposition ON managed_coop_import_sources(session_id, disposition_kind, neutralization_state)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_coop_import_source_operation ON managed_coop_import_sources(operation_id) WHERE operation_id IS NOT NULL");
            statement.execute("DROP INDEX IF EXISTS uq_managed_coop_import_source_resident");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_coop_import_imported_resident ON managed_coop_import_sources(resident_id) WHERE resident_id IS NOT NULL AND disposition_kind = 'IMPORTED'");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_coop_import_source_conflict ON managed_coop_import_sources(conflict_id) WHERE conflict_id IS NOT NULL");
        }
    }

    private void createImmutableEvidenceTriggers(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS trg_managed_coop_import_session_audit_immutable");
            statement.execute("DROP TRIGGER IF EXISTS trg_managed_coop_import_source_evidence_immutable");
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS trg_managed_coop_import_session_audit_immutable
                    BEFORE UPDATE OF session_id, authority_id, world_name, coop_id, x, y, z,
                        audit_version, audit_fingerprint, audit_envelope_json, audit_envelope_hash,
                        layout_id, coop_asset_id, resident_list_class_name, produce_payload,
                        produce_fingerprint, source_count, begin_command_id, created_at_ms
                    ON managed_coop_import_sessions
                    BEGIN
                        SELECT RAISE(ABORT, 'managed_coop_import_session_audit_immutable');
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS trg_managed_coop_import_source_evidence_immutable
                    BEFORE UPDATE OF source_id, session_id, source_fingerprint, source_envelope_json,
                        source_envelope_hash, source_payload, source_payload_hash,
                        locator_hints_json, locator_hints_hash, source_slot, source_order,
                        metadata_present, persistent_ref_present, persistent_uuid,
                        deployed_to_world, last_produced, profile_at_audit_id, role_id,
                        display_name, managed_snapshot_json, managed_snapshot_hash,
                        managed_snapshot_version, unavailable_fields_json, created_at_ms
                    ON managed_coop_import_sources
                    BEGIN
                        SELECT RAISE(ABORT, 'managed_coop_import_source_evidence_immutable');
                    END
                    """);
        }
    }
}
