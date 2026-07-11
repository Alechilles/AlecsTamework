package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.Statement;
import javax.annotation.Nonnull;

/**
 * Adds durable identity, managed-coop assignment, lifecycle-operation, and import-conflict storage.
 */
final class SqliteSchemaV5Migration {
    void apply(@Nonnull Connection connection) throws Exception {
        createRecoveryOperations(connection);
        createManagedCoopState(connection);
        createLifecycleOperations(connection);
        createImportConflicts(connection);
        reconcileLegacyData(connection);
        createUniqueIndexes(connection);
    }

    void reconcileLegacyData(@Nonnull Connection connection) throws Exception {
        classifyLegacyRecoveryRows(connection);
        classifyLegacyCoopRows(connection);
    }

    private void createRecoveryOperations(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npc_recovery_operations (
                        operation_id TEXT PRIMARY KEY,
                        profile_id TEXT NOT NULL,
                        source_npc_uuid TEXT,
                        planned_target_uuid TEXT,
                        actual_target_uuid TEXT,
                        state TEXT NOT NULL CHECK (state IN (
                            'PREPARED', 'SPAWN_CLAIMED', 'PROJECTION_CREATED',
                            'FINALIZED', 'LEGACY_UNVERIFIED', 'CONFLICT',
                            'FAILED', 'QUARANTINED'
                        )),
                        active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                        generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
                        attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        completed_at_ms INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_recovery_operations_profile ON npc_recovery_operations(profile_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_recovery_operations_target ON npc_recovery_operations(actual_target_uuid)");
        }
    }

    private void createManagedCoopState(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS managed_coop_authority (
                        authority_id TEXT PRIMARY KEY,
                        world_name TEXT NOT NULL,
                        coop_id TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        authority_state TEXT NOT NULL CHECK (authority_state IN (
                            'VANILLA_DISCOVERED', 'IMPORTING_TO_TWORK',
                            'TWORK_MANAGED', 'CONFLICT', 'DISABLED'
                        )),
                        active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                        import_version INTEGER NOT NULL DEFAULT 0,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        last_error TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS managed_coop_residents (
                        resident_id TEXT PRIMARY KEY,
                        authority_id TEXT NOT NULL,
                        world_name TEXT NOT NULL,
                        coop_id TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        resident_slot INTEGER NOT NULL,
                        profile_id TEXT NOT NULL,
                        role_id TEXT,
                        resident_uuid TEXT NOT NULL,
                        source_npc_uuid TEXT,
                        deployed_npc_uuid TEXT,
                        snapshot_json TEXT,
                        snapshot_hash TEXT,
                        snapshot_version INTEGER NOT NULL DEFAULT 1,
                        state TEXT NOT NULL CHECK (state IN (
                            'HOUSED', 'RELEASING', 'DEPLOYED',
                            'IMPORTING', 'QUARANTINED', 'RETIRED'
                        )),
                        generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
                        active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                        captured_at_ms INTEGER NOT NULL DEFAULT 0,
                        released_at_ms INTEGER NOT NULL DEFAULT 0,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE RESTRICT,
                        FOREIGN KEY (authority_id) REFERENCES managed_coop_authority(authority_id) ON DELETE RESTRICT
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_managed_coop_residents_coop ON managed_coop_residents(world_name, coop_id, x, y, z)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_managed_coop_residents_profile ON managed_coop_residents(profile_id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS managed_coop_uuid_claims (
                        npc_uuid TEXT PRIMARY KEY,
                        resident_id TEXT NOT NULL,
                        claim_kind TEXT NOT NULL CHECK (claim_kind IN ('SOURCE', 'DEPLOYED', 'PLANNED')),
                        active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        FOREIGN KEY (resident_id) REFERENCES managed_coop_residents(resident_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_managed_coop_uuid_claims_resident ON managed_coop_uuid_claims(resident_id)");
        }
    }

    private void createLifecycleOperations(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS coop_lifecycle_operations (
                        operation_id TEXT PRIMARY KEY,
                        operation_kind TEXT NOT NULL CHECK (operation_kind IN ('CAPTURE', 'RELEASE', 'EJECT', 'IMPORT')),
                        profile_id TEXT NOT NULL,
                        authority_id TEXT NOT NULL,
                        world_name TEXT NOT NULL,
                        coop_id TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        resident_slot INTEGER NOT NULL,
                        source_npc_uuid TEXT,
                        planned_target_uuid TEXT,
                        actual_target_uuid TEXT,
                        state TEXT NOT NULL CHECK (state IN (
                            'PREPARED', 'SLOT_COMMITTED', 'SOURCE_RETIRE_REQUESTED',
                            'SPAWN_CLAIMED', 'PROJECTION_CREATED', 'FINALIZED',
                            'COMPLETE', 'FAILED', 'QUARANTINED'
                        )),
                        snapshot_hash TEXT,
                        expected_generation INTEGER NOT NULL DEFAULT 0 CHECK (expected_generation >= 0),
                        generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
                        retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
                        active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        completed_at_ms INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE RESTRICT,
                        FOREIGN KEY (authority_id) REFERENCES managed_coop_authority(authority_id) ON DELETE RESTRICT
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_coop_lifecycle_profile ON coop_lifecycle_operations(profile_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_coop_lifecycle_coop ON coop_lifecycle_operations(world_name, coop_id, x, y, z)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_coop_lifecycle_target ON coop_lifecycle_operations(actual_target_uuid)");
        }
    }

    private void createImportConflicts(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS coop_import_conflicts (
                        conflict_id TEXT PRIMARY KEY,
                        authority_id TEXT NOT NULL,
                        world_name TEXT NOT NULL,
                        coop_id TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        resident_slot INTEGER,
                        conflict_kind TEXT NOT NULL,
                        source_fingerprint TEXT,
                        source_payload TEXT NOT NULL,
                        resolution_state TEXT NOT NULL DEFAULT 'UNRESOLVED' CHECK (
                            resolution_state IN ('UNRESOLVED', 'RESOLVED', 'IGNORED')
                        ),
                        created_at_ms INTEGER NOT NULL,
                        resolved_at_ms INTEGER NOT NULL DEFAULT 0,
                        resolution_note TEXT
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_coop_import_conflicts_coop ON coop_import_conflicts(world_name, coop_id, x, y, z)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_coop_import_conflicts_state ON coop_import_conflicts(resolution_state)");
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS trg_coop_import_conflict_source_immutable
                    BEFORE UPDATE OF source_fingerprint, source_payload ON coop_import_conflicts
                    BEGIN
                        SELECT RAISE(ABORT, 'coop_import_conflict_source_immutable');
                    END
                    """);
        }
    }

    private void classifyLegacyRecoveryRows(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT OR IGNORE INTO npc_recovery_operations (
                        operation_id, profile_id, source_npc_uuid, planned_target_uuid,
                        actual_target_uuid, state, active, generation, attempt_count,
                        created_at_ms, updated_at_ms, completed_at_ms, last_error
                    )
                    SELECT 'legacy-lost:' || s.snapshot_id, s.profile_id, p.current_npc_uuid,
                           json_extract(s.payload_json, '$.replacementNpcUuid'),
                           json_extract(s.payload_json, '$.replacementNpcUuid'),
                           CASE
                               WHEN a.profile_id = s.profile_id
                                    AND p.current_npc_uuid = json_extract(s.payload_json, '$.replacementNpcUuid')
                                   THEN 'FINALIZED'
                               WHEN a.profile_id IS NOT NULL AND a.profile_id <> s.profile_id THEN 'CONFLICT'
                               ELSE 'LEGACY_UNVERIFIED'
                           END,
                           CASE
                               WHEN a.profile_id = s.profile_id
                                    AND p.current_npc_uuid = json_extract(s.payload_json, '$.replacementNpcUuid')
                                   THEN 0 ELSE 1
                           END,
                           0, 0, s.created_at_ms, s.created_at_ms,
                           CASE
                               WHEN a.profile_id = s.profile_id
                                    AND p.current_npc_uuid = json_extract(s.payload_json, '$.replacementNpcUuid')
                                   THEN s.created_at_ms ELSE 0
                           END,
                           CASE
                               WHEN a.profile_id IS NOT NULL AND a.profile_id <> s.profile_id
                                   THEN 'legacy_replacement_maps_to_different_profile'
                               WHEN a.profile_id IS NULL THEN 'legacy_replacement_alias_unresolved'
                               ELSE NULL
                           END
                    FROM npc_snapshots s
                    INNER JOIN npc_profiles p ON p.profile_id = s.profile_id
                    LEFT JOIN npc_uuid_aliases a
                        ON a.npc_uuid = json_extract(s.payload_json, '$.replacementNpcUuid')
                    WHERE s.snapshot_type = 'lost' AND s.is_active = 1
                      AND json_extract(s.payload_json, '$.replacementNpcUuid') IS NOT NULL
                    """);
        }
    }

    private void classifyLegacyCoopRows(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT OR IGNORE INTO managed_coop_authority (
                        authority_id, world_name, coop_id, x, y, z, authority_state, active,
                        import_version, created_at_ms, updated_at_ms, last_error
                    )
                    SELECT replace(world_name, '|', '||') || '|' || x || '|' || y || '|' || z,
                           world_name, MIN(coop_id), x, y, z, 'TWORK_MANAGED', 1, 1,
                           MIN(updated_at_ms), MAX(updated_at_ms), NULL
                    FROM coop_slots GROUP BY world_name, x, y, z
                    """);
            insertLegacyCoopConflicts(statement);
            insertLegacyManagedResidents(statement);
            insertLegacyUuidClaims(statement);
        }
    }

    private void insertLegacyCoopConflicts(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                INSERT OR IGNORE INTO coop_import_conflicts (
                    conflict_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    conflict_kind, source_fingerprint, source_payload, resolution_state, created_at_ms
                )
                SELECT 'legacy-coop:' || c.rowid,
                       replace(c.world_name, '|', '||') || '|' || c.x || '|' || c.y || '|' || c.z,
                       c.world_name, c.coop_id, c.x, c.y, c.z, c.resident_slot,
                       CASE
                           WHEN c.profile_id IS NULL THEN 'MISSING_PROFILE'
                           WHEN COALESCE(c.last_released_npc_uuid, c.housed_npc_uuid) IS NULL THEN 'MISSING_UUID'
                           ELSE 'DUPLICATE_PROFILE_OR_UUID'
                       END,
                       COALESCE(c.profile_id, '') || '|' || COALESCE(c.last_released_npc_uuid, c.housed_npc_uuid, ''),
                       printf('world=%s;coop=%s;pos=%d,%d,%d;slot=%d;profile=%s;housed=%s;released=%s;snapshot=%s',
                              c.world_name, c.coop_id, c.x, c.y, c.z, c.resident_slot,
                              COALESCE(c.profile_id, ''), COALESCE(c.housed_npc_uuid, ''),
                              COALESCE(c.last_released_npc_uuid, ''), COALESCE(c.state_snapshot_json, '')),
                       'UNRESOLVED', c.updated_at_ms
                FROM coop_slots c
                WHERE c.profile_id IS NULL
                   OR COALESCE(c.last_released_npc_uuid, c.housed_npc_uuid) IS NULL
                   OR EXISTS (
                       SELECT 1 FROM coop_slots earlier WHERE earlier.rowid < c.rowid AND (
                           earlier.profile_id = c.profile_id
                           OR (c.housed_npc_uuid IS NOT NULL
                               AND c.housed_npc_uuid IN (earlier.housed_npc_uuid, earlier.last_released_npc_uuid))
                           OR (c.last_released_npc_uuid IS NOT NULL
                               AND c.last_released_npc_uuid IN (earlier.housed_npc_uuid, earlier.last_released_npc_uuid))
                       )
                   )
                """);
    }

    private void insertLegacyManagedResidents(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                INSERT OR IGNORE INTO managed_coop_residents (
                    resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    profile_id, role_id, resident_uuid, source_npc_uuid, deployed_npc_uuid,
                    snapshot_json, snapshot_hash, snapshot_version, state, generation, active,
                    captured_at_ms, released_at_ms, created_at_ms, updated_at_ms
                )
                SELECT 'legacy:' || c.world_name || ':' || c.coop_id || ':'
                           || c.x || ':' || c.y || ':' || c.z || ':' || c.resident_slot,
                       replace(c.world_name, '|', '||') || '|' || c.x || '|' || c.y || '|' || c.z,
                       c.world_name, c.coop_id, c.x, c.y, c.z, c.resident_slot,
                       c.profile_id, p.role_id,
                       COALESCE(c.last_released_npc_uuid, c.housed_npc_uuid),
                       c.housed_npc_uuid, c.last_released_npc_uuid,
                       c.state_snapshot_json, NULL, 1,
                       CASE WHEN c.housed_npc_uuid IS NOT NULL THEN 'HOUSED' ELSE 'DEPLOYED' END,
                       0, 1, c.captured_at_ms, c.released_at_ms, c.updated_at_ms, c.updated_at_ms
                FROM coop_slots c INNER JOIN npc_profiles p ON p.profile_id = c.profile_id
                WHERE COALESCE(c.last_released_npc_uuid, c.housed_npc_uuid) IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM coop_slots earlier WHERE earlier.rowid < c.rowid AND (
                          earlier.profile_id = c.profile_id
                          OR (c.housed_npc_uuid IS NOT NULL
                              AND c.housed_npc_uuid IN (earlier.housed_npc_uuid, earlier.last_released_npc_uuid))
                          OR (c.last_released_npc_uuid IS NOT NULL
                              AND c.last_released_npc_uuid IN (earlier.housed_npc_uuid, earlier.last_released_npc_uuid))
                      )
                  )
                """);
    }

    private void insertLegacyUuidClaims(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                INSERT OR IGNORE INTO managed_coop_uuid_claims (
                    npc_uuid, resident_id, claim_kind, active, created_at_ms, updated_at_ms
                )
                SELECT source_npc_uuid, resident_id, 'SOURCE', 1, created_at_ms, updated_at_ms
                FROM managed_coop_residents WHERE active = 1 AND source_npc_uuid IS NOT NULL
                UNION ALL
                SELECT deployed_npc_uuid, resident_id, 'DEPLOYED', 1, created_at_ms, updated_at_ms
                FROM managed_coop_residents
                WHERE active = 1 AND deployed_npc_uuid IS NOT NULL
                  AND deployed_npc_uuid <> source_npc_uuid
                """);
    }

    private void createUniqueIndexes(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_recovery_active_profile ON npc_recovery_operations(profile_id) WHERE active = 1");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_recovery_planned_target ON npc_recovery_operations(planned_target_uuid) WHERE active = 1 AND planned_target_uuid IS NOT NULL");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_recovery_actual_target ON npc_recovery_operations(actual_target_uuid) WHERE active = 1 AND actual_target_uuid IS NOT NULL");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_coop_active_location ON managed_coop_authority(world_name, x, y, z) WHERE active = 1");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_resident_active_profile ON managed_coop_residents(profile_id) WHERE active = 1");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_resident_active_uuid ON managed_coop_residents(resident_uuid) WHERE active = 1");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_resident_active_slot ON managed_coop_residents(authority_id, resident_slot) WHERE active = 1");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_uuid_claim_resident_kind ON managed_coop_uuid_claims(resident_id, claim_kind) WHERE active = 1");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_coop_lifecycle_active_profile ON coop_lifecycle_operations(profile_id) WHERE active = 1");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_coop_lifecycle_active_slot ON coop_lifecycle_operations(authority_id, resident_slot) WHERE active = 1");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_coop_lifecycle_planned_target ON coop_lifecycle_operations(planned_target_uuid) WHERE active = 1 AND planned_target_uuid IS NOT NULL");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_coop_lifecycle_actual_target ON coop_lifecycle_operations(actual_target_uuid) WHERE active = 1 AND actual_target_uuid IS NOT NULL");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_coop_import_conflict_source ON coop_import_conflicts(authority_id, conflict_kind, source_fingerprint) WHERE source_fingerprint IS NOT NULL");
        }
    }
}
