package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.annotation.Nonnull;

/**
 * Applies SQLite schema migrations.
 */
public final class SqliteSchemaMigrator {
    public static final int SCHEMA_VERSION_V2 = 2;
    public static final int SCHEMA_VERSION_V3 = 3;
    public static final int MIGRATION_VERSION_LEGACY_DAT_IMPORT_V2 = 2001;
    public static final String MIGRATION_NAME_SCHEMA_V2 = "schema_v2";
    public static final String MIGRATION_NAME_SCHEMA_V3 = "schema_v3_api_profile_data";
    public static final String MIGRATION_NAME_LEGACY_DAT_IMPORT_V2 = "legacy_dat_import_v2";

    public void migrate(@Nonnull Connection connection) throws Exception {
        createMigrationsTable(connection);
        if (!isVersionApplied(connection, SCHEMA_VERSION_V2)) {
            applySchemaV2(connection);
            recordMigration(connection, SCHEMA_VERSION_V2, MIGRATION_NAME_SCHEMA_V2);
        }
        if (!isVersionApplied(connection, SCHEMA_VERSION_V3)) {
            applySchemaV3(connection);
            recordMigration(connection, SCHEMA_VERSION_V3, MIGRATION_NAME_SCHEMA_V3);
        }
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
            statement.execute(
                    """
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
                    """
            );
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_api_profile_data_profile_namespace ON api_profile_data(profile_id, namespace)"
            );
        }
    }
}
