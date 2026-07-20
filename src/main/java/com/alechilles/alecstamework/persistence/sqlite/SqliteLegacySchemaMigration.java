package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.annotation.Nonnull;

/**
 * Reasserts the idempotent v2-v4 schema prerequisites before later migrations run.
 *
 * <p>Some historical databases contain migration markers without every prerequisite
 * table, or contain the early two-column {@code npc_profiles} shape. Repair is
 * deliberately structural: it preserves every row and never fabricates identity,
 * snapshot, role, or live-projection evidence.</p>
 */
final class SqliteLegacySchemaMigration {
    void ensureV2(@Nonnull Connection connection) throws Exception {
        createProfilesTable(connection);
        ensureHistoricalProfileColumns(connection);
        createV2TablesAndIndexes(connection);
    }

    void ensureV3(@Nonnull Connection connection) throws Exception {
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
            statement.execute("CREATE INDEX IF NOT EXISTS idx_api_profile_data_profile_namespace "
                    + "ON api_profile_data(profile_id, namespace)");
        }
    }

    void ensureV4(@Nonnull Connection connection) throws Exception {
        if (hasColumn(connection, "coop_slots", "state_snapshot_json")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE coop_slots ADD COLUMN state_snapshot_json TEXT");
        }
    }

    private void createProfilesTable(@Nonnull Connection connection) throws Exception {
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
        }
    }

    private void ensureHistoricalProfileColumns(@Nonnull Connection connection) throws Exception {
        addColumnIfMissing(connection, "npc_profiles", "current_npc_uuid", "TEXT");
        addColumnIfMissing(connection, "npc_profiles", "owner_uuid", "TEXT");
        addColumnIfMissing(connection, "npc_profiles", "display_name", "TEXT");
        addColumnIfMissing(connection, "npc_profiles", "role_id", "TEXT");
        addColumnIfMissing(connection, "npc_profiles", "state_json", "TEXT");
        addColumnIfMissing(connection, "npc_profiles", "state_hash", "TEXT");
        addColumnIfMissing(connection, "npc_profiles", "last_world_name", "TEXT");
        addColumnIfMissing(connection, "npc_profiles", "created_at_ms", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "npc_profiles", "updated_at_ms", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "npc_profiles", "last_active_at_ms", "INTEGER NOT NULL DEFAULT 0");
    }

    private void createV2TablesAndIndexes(@Nonnull Connection connection) throws Exception {
        ensureUniqueCurrentUuidIndex(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_profiles_owner_uuid ON npc_profiles(owner_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_profiles_current_uuid "
                    + "ON npc_profiles(current_npc_uuid)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npc_uuid_aliases (
                        npc_uuid TEXT PRIMARY KEY,
                        profile_id TEXT NOT NULL,
                        is_current INTEGER NOT NULL,
                        mapped_at_ms INTEGER NOT NULL,
                        FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_uuid_aliases_profile_id "
                    + "ON npc_uuid_aliases(profile_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_uuid_aliases_current_profile "
                    + "ON npc_uuid_aliases(is_current, profile_id)");
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
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_tool_links_tool_uuid "
                    + "ON npc_tool_links(tool_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_tool_links_profile_id "
                    + "ON npc_tool_links(profile_id)");
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
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_snapshots_profile_type_active "
                    + "ON npc_snapshots(profile_id, snapshot_type, is_active)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npc_snapshots_type_active_created "
                    + "ON npc_snapshots(snapshot_type, is_active, created_at_ms)");
            createCoopAndStateTables(statement);
        }
    }

    private void ensureUniqueCurrentUuidIndex(@Nonnull Connection connection) throws Exception {
        if (hasUniqueSingleColumnIndex(connection, "npc_profiles", "current_npc_uuid")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE UNIQUE INDEX uq_npc_profiles_current_uuid "
                    + "ON npc_profiles(current_npc_uuid)");
        }
    }

    private void createCoopAndStateTables(@Nonnull Statement statement) throws Exception {
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
        statement.execute("CREATE INDEX IF NOT EXISTS idx_coop_slots_last_released_npc_uuid "
                + "ON coop_slots(last_released_npc_uuid)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_coop_slots_world_coop "
                + "ON coop_slots(world_name, coop_id)");
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

    private void addColumnIfMissing(
            @Nonnull Connection connection,
            @Nonnull String tableName,
            @Nonnull String columnName,
            @Nonnull String definition
    ) throws Exception {
        if (hasColumn(connection, tableName, columnName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean hasColumn(
            @Nonnull Connection connection,
            @Nonnull String tableName,
            @Nonnull String columnName
    ) throws Exception {
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

    private boolean hasUniqueSingleColumnIndex(
            @Nonnull Connection connection,
            @Nonnull String tableName,
            @Nonnull String columnName
    ) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet indexes = statement.executeQuery("PRAGMA index_list(" + tableName + ")")) {
            while (indexes.next()) {
                if (indexes.getInt("unique") != 1) {
                    continue;
                }
                String indexName = indexes.getString("name");
                try (Statement columnStatement = connection.createStatement();
                     ResultSet columns = columnStatement.executeQuery("PRAGMA index_info(" + indexName + ")")) {
                    if (columns.next()
                            && columnName.equalsIgnoreCase(columns.getString("name"))
                            && !columns.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
