package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.annotation.Nonnull;

/**
 * Applies ordered SQLite schema migrations and delegates the larger v5 migration.
 */
public final class SqliteSchemaMigrator {
    public static final int SCHEMA_VERSION_V2 = 2;
    public static final int SCHEMA_VERSION_V3 = 3;
    public static final int SCHEMA_VERSION_V4 = 4;
    public static final int SCHEMA_VERSION_V5 = 5;
    public static final int SCHEMA_VERSION_V6 = 6;
    public static final int MIGRATION_VERSION_LEGACY_DAT_IMPORT_V2 = 2001;
    public static final String MIGRATION_NAME_SCHEMA_V2 = "schema_v2";
    public static final String MIGRATION_NAME_SCHEMA_V3 = "schema_v3_api_profile_data";
    public static final String MIGRATION_NAME_SCHEMA_V4 = "schema_v4_coop_state_snapshot";
    public static final String MIGRATION_NAME_SCHEMA_V5 = "schema_v5_identity_and_lifecycle_operations";
    public static final String MIGRATION_NAME_SCHEMA_V6 = "schema_v6_companion_population_integrity";
    public static final String MIGRATION_NAME_LEGACY_DAT_IMPORT_V2 = "legacy_dat_import_v2";

    private final SqliteSchemaV5Migration schemaV5Migration = new SqliteSchemaV5Migration();

    public void migrate(@Nonnull Connection connection) throws Exception {
        migrateThrough(connection, SCHEMA_VERSION_V6);
    }

    /** Applies every Tamework-owned schema migration up to the requested version. */
    void migrateThrough(@Nonnull Connection connection, int targetVersion) throws Exception {
        if (targetVersion < SCHEMA_VERSION_V2 || targetVersion > SCHEMA_VERSION_V6) {
            throw new IllegalArgumentException("Unsupported schema target: " + targetVersion);
        }
        createMigrationsTable(connection);
        if (targetVersion >= SCHEMA_VERSION_V2 && !isVersionApplied(connection, SCHEMA_VERSION_V2)) {
            applySchemaV2(connection);
            recordMigration(connection, SCHEMA_VERSION_V2, MIGRATION_NAME_SCHEMA_V2);
        }
        if (targetVersion >= SCHEMA_VERSION_V3 && !isVersionApplied(connection, SCHEMA_VERSION_V3)) {
            applySchemaV3(connection);
            recordMigration(connection, SCHEMA_VERSION_V3, MIGRATION_NAME_SCHEMA_V3);
        }
        if (targetVersion >= SCHEMA_VERSION_V4 && !isVersionApplied(connection, SCHEMA_VERSION_V4)) {
            applySchemaV4(connection);
            recordMigration(connection, SCHEMA_VERSION_V4, MIGRATION_NAME_SCHEMA_V4);
        }
        if (targetVersion >= SCHEMA_VERSION_V5) {
            if (!isVersionApplied(connection, SCHEMA_VERSION_V5)) {
                schemaV5Migration.apply(connection);
                recordMigration(connection, SCHEMA_VERSION_V5, MIGRATION_NAME_SCHEMA_V5);
            } else {
                // A pre-existing v5 marker may come from an earlier rollout order. Reapplying
                // the idempotent DDL ensures every managed-coop table/index exists before v6.
                schemaV5Migration.apply(connection);
            }
        }
        if (targetVersion < SCHEMA_VERSION_V6) {
            return;
        }
        if (!isVersionApplied(connection, SCHEMA_VERSION_V6)) {
            applySchemaV6(connection);
            recordMigration(connection, SCHEMA_VERSION_V6, MIGRATION_NAME_SCHEMA_V6);
        } else {
            reconcileSchemaV6Data(connection);
        }
    }

    void reconcileSchemaV5Data(@Nonnull Connection connection) throws Exception {
        schemaV5Migration.reconcileLegacyData(connection);
    }

    private void createMigrationsTable(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        applied_at_ms INTEGER NOT NULL
                    )
                    """);
        }
    }

    public boolean isVersionApplied(@Nonnull Connection connection, int version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ? LIMIT 1"
        )) {
            statement.setInt(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public void recordMigration(@Nonnull Connection connection, int version, @Nonnull String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO schema_migrations (version, name, applied_at_ms)
                VALUES (?, ?, ?)
                ON CONFLICT(version) DO UPDATE SET
                    name = excluded.name,
                    applied_at_ms = excluded.applied_at_ms
                """
        )) {
            statement.setInt(1, version);
            statement.setString(2, name);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void applySchemaV2(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npc_profiles (
                        profile_id TEXT PRIMARY KEY,
                        current_npc_uuid TEXT UNIQUE,
                        owner_uuid TEXT,
                        display_name TEXT,
                        role_id TEXT,
                        state_json TEXT,
                        state_hash TEXT,
                        last_world_name TEXT,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        last_active_at_ms INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_profiles_owner_uuid ON npc_profiles(owner_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_profiles_current_uuid ON npc_profiles(current_npc_uuid)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npc_uuid_aliases (
                        npc_uuid TEXT PRIMARY KEY,
                        profile_id TEXT NOT NULL,
                        is_current INTEGER NOT NULL,
                        mapped_at_ms INTEGER NOT NULL,
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_uuid_aliases_profile_id ON npc_uuid_aliases(profile_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_uuid_aliases_current_profile ON npc_uuid_aliases(is_current, profile_id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npc_tool_links (
                        profile_id TEXT NOT NULL,
                        tool_uuid TEXT NOT NULL,
                        link_type TEXT NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY (profile_id, tool_uuid, link_type),
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_tool_links_tool_uuid ON npc_tool_links(tool_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_tool_links_profile_id ON npc_tool_links(profile_id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npc_snapshots (
                        snapshot_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        profile_id TEXT NOT NULL,
                        snapshot_type TEXT NOT NULL,
                        snapshot_version INTEGER NOT NULL,
                        payload_json TEXT NOT NULL,
                        is_active INTEGER NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_snapshots_profile_type_active ON npc_snapshots(profile_id, snapshot_type, is_active)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_snapshots_type_active_created ON npc_snapshots(snapshot_type, is_active, created_at_ms)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS coop_slots (
                        world_name TEXT NOT NULL,
                        coop_id TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        resident_slot INTEGER NOT NULL,
                        profile_id TEXT,
                        housed_npc_uuid TEXT,
                        last_released_npc_uuid TEXT,
                        captured_at_ms INTEGER NOT NULL,
                        released_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY (world_name, coop_id, x, y, z, resident_slot),
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE SET NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_coop_slots_profile_id ON coop_slots(profile_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_coop_slots_last_released_npc_uuid ON coop_slots(last_released_npc_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_coop_slots_world_coop ON coop_slots(world_name, coop_id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS profile_states (
                        profile_id TEXT PRIMARY KEY,
                        capture_active INTEGER NOT NULL,
                        death_active INTEGER NOT NULL,
                        lost_active INTEGER NOT NULL,
                        in_coop INTEGER NOT NULL,
                        coop_key TEXT,
                        updated_at_ms INTEGER NOT NULL,
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private void applySchemaV3(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS api_profile_data (
                        profile_id TEXT NOT NULL,
                        namespace TEXT NOT NULL,
                        data_key TEXT NOT NULL,
                        json_payload TEXT NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY (profile_id, namespace, data_key),
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_api_profile_data_profile_namespace ON api_profile_data(profile_id, namespace)");
        }
    }

    private void applySchemaV4(@Nonnull Connection connection) throws Exception {
        if (hasColumn(connection, "coop_slots", "state_snapshot_json")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE coop_slots ADD COLUMN state_snapshot_json TEXT");
        }
    }

    private void applySchemaV6(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS companion_population_state (
                        profile_id TEXT PRIMARY KEY,
                        ownership_world_name TEXT,
                        lifecycle_state TEXT NOT NULL,
                        physical_world_name TEXT,
                        physical_chunk_x INTEGER,
                        physical_chunk_z INTEGER,
                        revision INTEGER NOT NULL CHECK (revision >= 0),
                        source TEXT,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE,
                        CHECK ((physical_world_name IS NULL AND physical_chunk_x IS NULL AND physical_chunk_z IS NULL)
                            OR (physical_world_name IS NOT NULL AND physical_chunk_x IS NOT NULL AND physical_chunk_z IS NOT NULL))
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_companion_population_scope
                    ON companion_population_state(ownership_world_name, lifecycle_state)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_companion_population_physical_chunk
                    ON companion_population_state(physical_world_name, physical_chunk_x, physical_chunk_z, lifecycle_state)
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS companion_population_operations (
                        operation_id TEXT PRIMARY KEY,
                        profile_id TEXT NOT NULL,
                        operation_type TEXT NOT NULL,
                        state TEXT NOT NULL,
                        expected_revision INTEGER NOT NULL CHECK (expected_revision >= 0),
                        old_state_json TEXT NOT NULL,
                        new_state_json TEXT NOT NULL,
                        target_context_json TEXT,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        completed_at_ms INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_companion_population_operations_state
                    ON companion_population_operations(state, updated_at_ms)
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_companion_population_nonterminal_profile
                    ON companion_population_operations(profile_id)
                    WHERE state IN ('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING')
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS companion_population_reconciliation (
                        coverage_key TEXT PRIMARY KEY,
                        coverage_dimension TEXT NOT NULL,
                        world_or_save_id TEXT,
                        scan_generation TEXT NOT NULL,
                        state TEXT NOT NULL,
                        cursor_json TEXT,
                        scanned_count INTEGER NOT NULL DEFAULT 0 CHECK (scanned_count >= 0),
                        estimated_total INTEGER NOT NULL DEFAULT -1 CHECK (estimated_total >= -1),
                        started_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        completed_at_ms INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_companion_population_reconciliation_state
                    ON companion_population_reconciliation(coverage_dimension, state, updated_at_ms)
                    """);
            createCompanionPopulationScanSessionTable(statement);
            createCompanionPopulationEvidenceTable(statement);
            statement.execute("""
                    INSERT OR IGNORE INTO companion_population_state (
                        profile_id, ownership_world_name, lifecycle_state,
                        physical_world_name, physical_chunk_x, physical_chunk_z,
                        revision, source, created_at_ms, updated_at_ms
                    )
                    SELECT
                        p.profile_id,
                        p.last_world_name,
                        CASE
                            WHEN COALESCE(s.capture_active, 0) = 1 THEN 'CAPTURED'
                            WHEN COALESCE(s.death_active, 0) = 1 THEN 'DEAD_REVIVABLE'
                            WHEN COALESCE(s.lost_active, 0) = 1 THEN 'LOST'
                            WHEN COALESCE(s.in_coop, 0) = 1 THEN 'COOP'
                            ELSE 'UNKNOWN_DORMANT'
                        END,
                        NULL, NULL, NULL,
                        0,
                        'schema_v6_legacy_backfill',
                        p.created_at_ms,
                        p.updated_at_ms
                    FROM npc_profiles p
                    LEFT JOIN profile_states s ON s.profile_id = p.profile_id
                    """);
        }
    }

    private void reconcileSchemaV6Data(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            createCompanionPopulationScanSessionTable(statement);
            createCompanionPopulationEvidenceTable(statement);
            statement.execute("""
                    INSERT OR IGNORE INTO companion_population_state (
                        profile_id, ownership_world_name, lifecycle_state,
                        physical_world_name, physical_chunk_x, physical_chunk_z,
                        revision, source, created_at_ms, updated_at_ms
                    )
                    SELECT
                        p.profile_id,
                        p.last_world_name,
                        CASE
                            WHEN COALESCE(s.capture_active, 0) = 1 THEN 'CAPTURED'
                            WHEN COALESCE(s.death_active, 0) = 1 THEN 'DEAD_REVIVABLE'
                            WHEN COALESCE(s.lost_active, 0) = 1 THEN 'LOST'
                            WHEN COALESCE(s.in_coop, 0) = 1 THEN 'COOP'
                            ELSE 'UNKNOWN_DORMANT'
                        END,
                        NULL, NULL, NULL,
                        0,
                        'schema_v6_runtime_backfill',
                        p.created_at_ms,
                        p.updated_at_ms
                    FROM npc_profiles p
                    LEFT JOIN profile_states s ON s.profile_id = p.profile_id
                    """);
        }
    }

    private void createCompanionPopulationScanSessionTable(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS companion_population_scan_session (
                    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
                    epoch TEXT NOT NULL CHECK (length(epoch) > 0),
                    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'READY')),
                    started_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    completed_at_ms INTEGER NOT NULL DEFAULT 0
                )
                """);
    }

    private void createCompanionPopulationEvidenceTable(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS companion_population_reconciliation_evidence (
                    coverage_key TEXT NOT NULL,
                    scan_generation TEXT NOT NULL,
                    evidence_key TEXT NOT NULL,
                    npc_uuid TEXT NOT NULL,
                    owner_uuid TEXT,
                    evidence_kind TEXT NOT NULL,
                    ownership_world_name TEXT,
                    physical_world_name TEXT,
                    physical_chunk_x INTEGER,
                    physical_chunk_z INTEGER,
                    source TEXT NOT NULL,
                    observed_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (coverage_key, scan_generation, evidence_key),
                    CHECK ((physical_world_name IS NULL AND physical_chunk_x IS NULL AND physical_chunk_z IS NULL)
                        OR (physical_world_name IS NOT NULL AND physical_chunk_x IS NOT NULL AND physical_chunk_z IS NOT NULL))
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_companion_population_evidence_identity
                ON companion_population_reconciliation_evidence(npc_uuid, scan_generation)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_companion_population_evidence_source
                ON companion_population_reconciliation_evidence(coverage_key, scan_generation)
                """);
    }

    private boolean hasColumn(@Nonnull Connection connection,
                              @Nonnull String tableName,
                              @Nonnull String columnName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
