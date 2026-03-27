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
    private static final String SCHEMA_V1_ID = "schema_v1";

    public void migrate(@Nonnull Connection connection) throws Exception {
        createMigrationsTable(connection);
        if (isMigrationApplied(connection, SCHEMA_V1_ID)) {
            return;
        }
        applySchemaV1(connection);
        recordMigration(connection, SCHEMA_V1_ID);
    }

    private void createMigrationsTable(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        migration_id TEXT PRIMARY KEY,
                        applied_at_ms INTEGER NOT NULL
                    )
                    """);
        }
    }

    private boolean isMigrationApplied(@Nonnull Connection connection, @Nonnull String migrationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE migration_id = ? LIMIT 1"
        )) {
            statement.setString(1, migrationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void recordMigration(@Nonnull Connection connection, @Nonnull String migrationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_migrations (migration_id, applied_at_ms) VALUES (?, ?)"
        )) {
            statement.setString(1, migrationId);
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void applySchemaV1(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE capture_snapshots (
                        npc_uuid TEXT PRIMARY KEY,
                        owner_uuid TEXT,
                        role_id TEXT,
                        display_name TEXT,
                        last_known_x REAL,
                        last_known_y REAL,
                        last_known_z REAL,
                        home_x REAL,
                        home_y REAL,
                        home_z REAL,
                        captured_at_ms INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX idx_capture_owner_uuid ON capture_snapshots(owner_uuid)");

            statement.execute("""
                    CREATE TABLE capture_snapshot_tools (
                        npc_uuid TEXT NOT NULL,
                        tool_id TEXT NOT NULL,
                        PRIMARY KEY (npc_uuid, tool_id),
                        FOREIGN KEY (npc_uuid) REFERENCES capture_snapshots(npc_uuid) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX idx_capture_tools_npc_uuid ON capture_snapshot_tools(npc_uuid)");

            statement.execute("""
                    CREATE TABLE coop_slot_ledger (
                        slot_key TEXT PRIMARY KEY,
                        world_name TEXT,
                        coop_id TEXT,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        resident_slot INTEGER NOT NULL,
                        housed_npc_uuid TEXT,
                        last_released_npc_uuid TEXT,
                        owner_uuid TEXT,
                        role_id TEXT,
                        display_name TEXT,
                        housed_at_ms INTEGER NOT NULL,
                        released_at_ms INTEGER NOT NULL,
                        state_snapshot_json TEXT
                    )
                    """);
            statement.execute("CREATE INDEX idx_coop_housed_npc_uuid ON coop_slot_ledger(housed_npc_uuid)");
            statement.execute("CREATE INDEX idx_coop_last_released_npc_uuid ON coop_slot_ledger(last_released_npc_uuid)");
            statement.execute("CREATE INDEX idx_coop_owner_uuid ON coop_slot_ledger(owner_uuid)");
            statement.execute("""
                    CREATE UNIQUE INDEX idx_coop_slot_identity ON coop_slot_ledger(
                        world_name, coop_id, x, y, z, resident_slot
                    )
                    """);

            statement.execute("""
                    CREATE TABLE coop_slot_tools (
                        slot_key TEXT NOT NULL,
                        tool_id TEXT NOT NULL,
                        PRIMARY KEY (slot_key, tool_id),
                        FOREIGN KEY (slot_key) REFERENCES coop_slot_ledger(slot_key) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX idx_coop_tools_slot_key ON coop_slot_tools(slot_key)");

            statement.execute("""
                    CREATE TABLE death_snapshots (
                        npc_uuid TEXT PRIMARY KEY,
                        owner_uuid TEXT,
                        owner_name TEXT,
                        role_id TEXT,
                        tamed INTEGER NOT NULL,
                        custom_name TEXT,
                        display_name TEXT,
                        last_known_x REAL,
                        last_known_y REAL,
                        last_known_z REAL,
                        home_x REAL,
                        home_y REAL,
                        home_z REAL,
                        died_at_ms INTEGER NOT NULL,
                        respawn_available_at_ms INTEGER NOT NULL,
                        breeding_config_id TEXT,
                        breeding_happiness REAL,
                        breeding_cooldown_until_ms INTEGER NOT NULL,
                        breeding_last_partner_uuid TEXT,
                        traits_config_id TEXT,
                        traits_roll_seed INTEGER NOT NULL,
                        traits_values TEXT,
                        happiness_config_id TEXT,
                        happiness_value REAL,
                        happiness_last_update_ms INTEGER NOT NULL,
                        life_stage TEXT,
                        life_stage_born_at_ms INTEGER NOT NULL,
                        life_stage_adolescent_at_ms INTEGER NOT NULL,
                        life_stage_adult_at_ms INTEGER NOT NULL,
                        life_stage_fully_grown_at_ms INTEGER NOT NULL,
                        life_stage_baby_scale REAL NOT NULL,
                        life_stage_adolescent_scale REAL NOT NULL,
                        life_stage_adolescent_switch_scale REAL NOT NULL,
                        life_stage_adult_start_scale REAL NOT NULL,
                        life_stage_adult_switch_scale REAL NOT NULL,
                        life_stage_adult_scale REAL NOT NULL,
                        life_stage_growth_scaling_enabled INTEGER NOT NULL,
                        attachments_config_id TEXT,
                        attachments_values TEXT,
                        breeding_enabled INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX idx_death_owner_uuid ON death_snapshots(owner_uuid)");

            statement.execute("""
                    CREATE TABLE death_snapshot_tools (
                        npc_uuid TEXT NOT NULL,
                        tool_id TEXT NOT NULL,
                        PRIMARY KEY (npc_uuid, tool_id),
                        FOREIGN KEY (npc_uuid) REFERENCES death_snapshots(npc_uuid) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX idx_death_tools_npc_uuid ON death_snapshot_tools(npc_uuid)");

            statement.execute("""
                    CREATE TABLE lost_snapshots (
                        npc_uuid TEXT PRIMARY KEY,
                        last_known_x REAL,
                        last_known_y REAL,
                        last_known_z REAL,
                        home_x REAL,
                        home_y REAL,
                        home_z REAL,
                        last_relocation_queued_at_ms INTEGER NOT NULL,
                        lost_at_ms INTEGER NOT NULL,
                        relocation_retry_attempts INTEGER NOT NULL,
                        replacement_npc_uuid TEXT,
                        recovered_at_ms INTEGER NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE npc_profiles (
                        npc_uuid TEXT PRIMARY KEY,
                        owner_uuid TEXT,
                        owner_name TEXT,
                        role_id TEXT,
                        display_name TEXT,
                        custom_name TEXT,
                        tamed INTEGER,
                        coop_id TEXT,
                        coop_slot INTEGER,
                        profile_json TEXT,
                        updated_at_ms INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX idx_npc_profiles_owner_uuid ON npc_profiles(owner_uuid)");

            statement.execute("""
                    CREATE TABLE npc_profile_tool_links (
                        npc_uuid TEXT NOT NULL,
                        tool_id TEXT NOT NULL,
                        PRIMARY KEY (npc_uuid, tool_id),
                        FOREIGN KEY (npc_uuid) REFERENCES npc_profiles(npc_uuid) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX idx_npc_profile_tools_npc_uuid ON npc_profile_tool_links(npc_uuid)");
        }
    }
}
