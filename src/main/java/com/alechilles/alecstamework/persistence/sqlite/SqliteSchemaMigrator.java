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
    public static final int SCHEMA_VERSION_V7 = 7;
    public static final int SCHEMA_VERSION_V8 = 8;
    public static final int MIGRATION_VERSION_LEGACY_DAT_IMPORT_V2 = 2001;
    public static final String MIGRATION_NAME_SCHEMA_V2 = "schema_v2";
    public static final String MIGRATION_NAME_SCHEMA_V3 = "schema_v3_api_profile_data";
    public static final String MIGRATION_NAME_SCHEMA_V4 = "schema_v4_coop_state_snapshot";
    public static final String MIGRATION_NAME_SCHEMA_V5 = "schema_v5_identity_and_lifecycle_operations";
    public static final String MIGRATION_NAME_SCHEMA_V6 = "schema_v6_companion_population_integrity";
    public static final String MIGRATION_NAME_SCHEMA_V7 = "schema_v7_persistence_resilience";
    public static final String MIGRATION_NAME_SCHEMA_V8 = "schema_v8_hydragon_integration_foundations";
    public static final String MIGRATION_NAME_LEGACY_DAT_IMPORT_V2 = "legacy_dat_import_v2";

    private final SqliteLegacySchemaMigration legacySchemaMigration = new SqliteLegacySchemaMigration();
    private final SqliteSchemaV5Migration schemaV5Migration = new SqliteSchemaV5Migration();
    private final SqliteSchemaV7Migration schemaV7Migration = new SqliteSchemaV7Migration();
    private final SqliteSchemaV8Migration schemaV8Migration = new SqliteSchemaV8Migration();

    public void migrate(@Nonnull Connection connection) throws Exception {
        migrateThrough(connection, SCHEMA_VERSION_V8);
    }

    /** Applies every Tamework-owned schema migration up to the requested version. */
    void migrateThrough(@Nonnull Connection connection, int targetVersion) throws Exception {
        if (targetVersion < SCHEMA_VERSION_V2 || targetVersion > SCHEMA_VERSION_V8) {
            throw new IllegalArgumentException("Unsupported schema target: " + targetVersion);
        }
        createMigrationsTable(connection);
        if (targetVersion >= SCHEMA_VERSION_V2) {
            legacySchemaMigration.ensureV2(connection);
            if (!isVersionApplied(connection, SCHEMA_VERSION_V2)) {
                recordMigration(connection, SCHEMA_VERSION_V2, MIGRATION_NAME_SCHEMA_V2);
            }
        }
        if (targetVersion >= SCHEMA_VERSION_V3) {
            legacySchemaMigration.ensureV3(connection);
            if (!isVersionApplied(connection, SCHEMA_VERSION_V3)) {
                recordMigration(connection, SCHEMA_VERSION_V3, MIGRATION_NAME_SCHEMA_V3);
            }
        }
        if (targetVersion >= SCHEMA_VERSION_V4) {
            legacySchemaMigration.ensureV4(connection);
            if (!isVersionApplied(connection, SCHEMA_VERSION_V4)) {
                recordMigration(connection, SCHEMA_VERSION_V4, MIGRATION_NAME_SCHEMA_V4);
            }
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
        if (targetVersion < SCHEMA_VERSION_V7) {
            return;
        }
        schemaV7Migration.apply(connection);
        if (!isVersionApplied(connection, SCHEMA_VERSION_V7)) {
            recordMigration(connection, SCHEMA_VERSION_V7, MIGRATION_NAME_SCHEMA_V7);
        }
        if (targetVersion < SCHEMA_VERSION_V8) {
            return;
        }
        schemaV8Migration.apply(connection);
        if (!isVersionApplied(connection, SCHEMA_VERSION_V8)) {
            recordMigration(connection, SCHEMA_VERSION_V8, MIGRATION_NAME_SCHEMA_V8);
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
        if (!hasColumn(connection, "schema_migrations", "applied_at_ms")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE schema_migrations "
                        + "ADD COLUMN applied_at_ms INTEGER NOT NULL DEFAULT 0");
            }
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
