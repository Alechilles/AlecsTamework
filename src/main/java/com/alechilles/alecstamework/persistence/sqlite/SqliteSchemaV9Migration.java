package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.annotation.Nonnull;

/**
 * Replaces the unreleased bonded-vessel experiment with the generic command-family authorities.
 * All DDL is idempotent so startup can safely reconcile a previously marked migration.
 */
final class SqliteSchemaV9Migration {

    void apply(@Nonnull Connection connection) throws Exception {
        ensureCaptureAttemptExtensions(connection);
        try (Statement statement = connection.createStatement()) {
            dropObsoleteBondedVessels(statement);
            createCommandFamilyRosters(statement);
            createProvisioningCommandLinks(statement);
            createTimedSummons(statement);
            createPaidRevival(statement);
            createCaptureSourceRefunds(statement);
        }
    }

    private void createCaptureSourceRefunds(Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS capture_source_refund_claims (
                    attempt_id TEXT PRIMARY KEY REFERENCES capture_attempts(attempt_id)
                        ON DELETE CASCADE,
                    owner_uuid TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity = 1),
                    state TEXT NOT NULL CHECK (state IN ('PENDING','DELIVERED')),
                    reason TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_capture_source_refunds_owner_state
                ON capture_source_refund_claims(owner_uuid, state, created_at_ms)
                """);
    }

    private void createProvisioningCommandLinks(Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS companion_provisioning_command_links (
                    operation_id TEXT PRIMARY KEY,
                    caller_namespace TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    owner_uuid TEXT NOT NULL,
                    command_family_id TEXT NOT NULL,
                    required_command_config_id TEXT NOT NULL,
                    access_item_id TEXT,
                    group_id TEXT,
                    active_for_bulk_commands INTEGER NOT NULL CHECK (
                        active_for_bulk_commands IN (0, 1)),
                    home_x REAL,
                    home_y REAL,
                    home_z REAL,
                    expected_roster_revision INTEGER NOT NULL CHECK (expected_roster_revision >= 0),
                    profile_id TEXT,
                    resulting_roster_revision INTEGER CHECK (
                        resulting_roster_revision IS NULL OR resulting_roster_revision >= 1),
                    state TEXT NOT NULL CHECK (state IN ('PREPARED','COMMITTED','QUARANTINED')),
                    reason TEXT,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    UNIQUE (caller_namespace, idempotency_key),
                    CHECK ((home_x IS NULL AND home_y IS NULL AND home_z IS NULL)
                        OR (home_x IS NOT NULL AND home_y IS NOT NULL AND home_z IS NOT NULL))
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_companion_provisioning_command_links_state
                ON companion_provisioning_command_links(state, updated_at_ms, operation_id)
                """);
    }

    private void ensureCaptureAttemptExtensions(Connection connection) throws Exception {
        addColumnIfMissing(connection, "capture_attempts", "source_consumption",
                "TEXT NOT NULL DEFAULT 'SUCCESS_ONLY' CHECK "
                        + "(source_consumption IN ('SUCCESS_ONLY','RESOLVED_ATTEMPT'))");
        addColumnIfMissing(connection, "capture_attempts", "success_disposition",
                "TEXT NOT NULL DEFAULT 'CAPTURED_ITEM' CHECK "
                        + "(success_disposition IN ('CAPTURED_ITEM','TAME_AND_COMMAND_LINK'))");
        addColumnIfMissing(connection, "capture_attempts", "command_family_id", "TEXT");
        addColumnIfMissing(connection, "capture_attempts", "required_command_config_id", "TEXT");
        addColumnIfMissing(connection, "capture_attempts", "require_command_access_item",
                "INTEGER NOT NULL DEFAULT 0 CHECK (require_command_access_item IN (0,1))");
        addColumnIfMissing(connection, "capture_attempts", "source_spend_state",
                "TEXT NOT NULL DEFAULT 'NOT_REQUIRED' CHECK "
                        + "(source_spend_state IN ('NOT_REQUIRED','PENDING','CONSUMED'))");
        addColumnIfMissing(connection, "capture_attempts", "source_spend_before_fingerprint", "TEXT");
        addColumnIfMissing(connection, "capture_attempts", "source_spend_after_fingerprint", "TEXT");
        addColumnIfMissing(connection, "capture_attempts", "source_spend_receipted_at_ms",
                "INTEGER NOT NULL DEFAULT 0 CHECK (source_spend_receipted_at_ms >= 0)");
        addColumnIfMissing(connection, "capture_attempts", "source_spend_at_ms",
                "INTEGER NOT NULL DEFAULT 0 CHECK (source_spend_at_ms >= 0)");
    }

    private void addColumnIfMissing(Connection connection, String table, String column,
                                    String definition) throws Exception {
        if (hasColumn(connection, table, column)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return true;
            }
            return false;
        }
    }

    private void dropObsoleteBondedVessels(Statement statement) throws Exception {
        statement.execute("DROP TABLE IF EXISTS bonded_vessel_operations");
        statement.execute("DROP TABLE IF EXISTS bonded_vessel_bindings");
    }

    private void createCommandFamilyRosters(Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS command_family_rosters (
                    owner_uuid TEXT NOT NULL,
                    command_family_id TEXT NOT NULL,
                    row_revision INTEGER NOT NULL DEFAULT 0 CHECK (row_revision >= 0),
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (owner_uuid, command_family_id)
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS command_family_roster_memberships (
                    owner_uuid TEXT NOT NULL,
                    command_family_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    role_id TEXT NOT NULL,
                    profile_revision INTEGER NOT NULL CHECK (profile_revision >= 0),
                    command_state TEXT NOT NULL DEFAULT 'ROSTER_STORED' CHECK (command_state IN (
                        'ROSTER_STORED','RESTORING','ACTIVE','UNLOADED','STORING',
                        'DEAD_REVIVABLE','LOST')),
                    group_id TEXT,
                    active_for_bulk_commands INTEGER NOT NULL DEFAULT 0 CHECK (
                        active_for_bulk_commands IN (0, 1)),
                    home_x REAL,
                    home_y REAL,
                    home_z REAL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (owner_uuid, command_family_id, profile_id),
                    FOREIGN KEY (owner_uuid, command_family_id)
                        REFERENCES command_family_rosters(owner_uuid, command_family_id)
                        ON DELETE CASCADE,
                    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE,
                    CHECK ((home_x IS NULL AND home_y IS NULL AND home_z IS NULL)
                        OR (home_x IS NOT NULL AND home_y IS NOT NULL AND home_z IS NOT NULL))
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_command_family_roster_profile
                ON command_family_roster_memberships(profile_id, owner_uuid, command_family_id)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_command_family_roster_active
                ON command_family_roster_memberships(
                    owner_uuid, command_family_id, active_for_bulk_commands, profile_id)
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS command_family_roster_operations (
                    operation_id TEXT PRIMARY KEY,
                    caller_namespace TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    correlation_id TEXT,
                    owner_uuid TEXT NOT NULL,
                    command_family_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    operation_kind TEXT NOT NULL CHECK (operation_kind IN ('UPSERT', 'REMOVE')),
                    expected_revision INTEGER NOT NULL CHECK (expected_revision >= 0),
                    expected_profile_revision INTEGER NOT NULL CHECK (expected_profile_revision >= 0),
                    required_command_config_id TEXT,
                    access_item_id TEXT,
                    profile_role_id TEXT,
                    resulting_revision INTEGER NOT NULL CHECK (resulting_revision >= 0),
                    payload_fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL CHECK (status IN ('APPLIED', 'IDEMPOTENT', 'CONFLICT', 'NOT_FOUND')),
                    reason TEXT,
                    created_at_ms INTEGER NOT NULL,
                    completed_at_ms INTEGER NOT NULL,
                    UNIQUE (caller_namespace, idempotency_key)
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_command_family_roster_operations_target
                ON command_family_roster_operations(owner_uuid, command_family_id, profile_id, created_at_ms)
                """);
    }

    private void createTimedSummons(Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS command_timed_summon_sessions (
                    owner_uuid TEXT NOT NULL,
                    command_family_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    row_revision INTEGER NOT NULL DEFAULT 1 CHECK (row_revision >= 1),
                    summon_state TEXT NOT NULL CHECK (summon_state IN (
                        'ROSTER_STORED','RESTORING','ACTIVE','UNLOADED','STORING',
                        'DEAD_REVIVABLE','LOST')),
                    summon_session_id TEXT,
                    summon_remaining_ms INTEGER CHECK (
                        summon_remaining_ms IS NULL OR summon_remaining_ms >= 0),
                    resummon_cooldown_until_ms INTEGER NOT NULL DEFAULT 0 CHECK (
                        resummon_cooldown_until_ms >= 0),
                    summon_config_id TEXT,
                    summon_config_revision INTEGER CHECK (
                        summon_config_revision IS NULL OR summon_config_revision >= 0),
                    summon_policy_json TEXT NOT NULL DEFAULT '{}',
                    warning_receipts_json TEXT NOT NULL DEFAULT '[]',
                    summon_last_checkpoint_at_ms INTEGER,
                    active_operation_id TEXT,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (owner_uuid, command_family_id, profile_id),
                    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE,
                    FOREIGN KEY (owner_uuid, command_family_id, profile_id)
                        REFERENCES command_family_roster_memberships(
                            owner_uuid, command_family_id, profile_id) ON DELETE CASCADE,
                    CHECK ((summon_state IN ('RESTORING','ACTIVE','UNLOADED','STORING')
                            AND summon_session_id IS NOT NULL
                            AND summon_last_checkpoint_at_ms IS NOT NULL)
                        OR (summon_state IN ('ROSTER_STORED','DEAD_REVIVABLE','LOST')
                            AND summon_session_id IS NULL
                            AND summon_remaining_ms IS NULL
                            AND summon_last_checkpoint_at_ms IS NULL))
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_command_timed_sessions_owner_state
                ON command_timed_summon_sessions(owner_uuid, command_family_id, summon_state)
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS command_timed_summon_snapshots (
                    owner_uuid TEXT NOT NULL,
                    command_family_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    source_npc_uuid TEXT NOT NULL,
                    snapshot_json TEXT NOT NULL,
                    snapshot_sha256 TEXT NOT NULL CHECK (length(snapshot_sha256) = 64),
                    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= 0),
                    PRIMARY KEY (owner_uuid, command_family_id, profile_id),
                    FOREIGN KEY (owner_uuid, command_family_id, profile_id)
                        REFERENCES command_timed_summon_sessions(
                            owner_uuid, command_family_id, profile_id) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_command_timed_sessions_expiry
                ON command_timed_summon_sessions(
                    summon_state, summon_last_checkpoint_at_ms, summon_remaining_ms)
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS command_timed_summon_operations (
                    operation_id TEXT PRIMARY KEY,
                    caller_namespace TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    owner_uuid TEXT NOT NULL,
                    command_family_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    operation_kind TEXT NOT NULL CHECK (operation_kind IN (
                        'SUMMON','ACTIVATE','CHECKPOINT','STORE','COMPLETE_STORAGE','MARK_DEAD','MARK_LOST')),
                    operation_state TEXT NOT NULL CHECK (operation_state IN (
                        'PREPARED','APPLYING','COMMITTED','CANCELED','QUARANTINED')),
                    expected_state TEXT NOT NULL CHECK (expected_state IN (
                        'ROSTER_STORED','RESTORING','ACTIVE','UNLOADED','STORING',
                        'DEAD_REVIVABLE','LOST')),
                    expected_row_revision INTEGER NOT NULL CHECK (expected_row_revision >= 0),
                    expected_profile_revision INTEGER CHECK (
                        expected_profile_revision IS NULL OR expected_profile_revision >= 0),
                    population_operation_id TEXT,
                    projection_npc_uuid TEXT,
                    resulting_row_revision INTEGER CHECK (
                        resulting_row_revision IS NULL OR resulting_row_revision >= 1),
                    summon_session_id TEXT,
                    result_state TEXT CHECK (result_state IS NULL OR result_state IN (
                        'ROSTER_STORED','RESTORING','ACTIVE','UNLOADED','STORING',
                        'DEAD_REVIVABLE','LOST')),
                    reason TEXT,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    completed_at_ms INTEGER NOT NULL DEFAULT 0,
                    UNIQUE (caller_namespace, idempotency_key),
                    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_command_timed_operations_recovery
                ON command_timed_summon_operations(operation_state, updated_at_ms, operation_id)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_command_timed_operations_profile
                ON command_timed_summon_operations(
                    owner_uuid, command_family_id, profile_id, created_at_ms)
                """);
    }

    private void createPaidRevival(Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS paid_command_revival_operations (
                    operation_id TEXT PRIMARY KEY,
                    caller_namespace TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    owner_uuid TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    command_family_id TEXT NOT NULL,
                    role_id TEXT NOT NULL,
                    config_id TEXT,
                    config_revision TEXT NOT NULL,
                    death_revision INTEGER NOT NULL CHECK (death_revision >= 0),
                    profile_revision INTEGER NOT NULL CHECK (profile_revision >= 0),
                    population_admission_operation_id TEXT,
                    placement_fingerprint TEXT,
                    revive_projection_operation_id TEXT,
                    state TEXT NOT NULL CHECK (state IN (
                        'PREPARED','RESERVED','COST_CONSUMED','APPLYING','SUCCEEDED','CANCELED',
                        'REFUND_REQUIRED','REFUNDED','QUARANTINED')),
                    detail TEXT,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    completed_at_ms INTEGER,
                    UNIQUE (caller_namespace, idempotency_key),
                    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_paid_command_revival_owner_profile_state
                ON paid_command_revival_operations(owner_uuid, profile_id, state)
                """);
        statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_paid_command_revival_active_profile
                ON paid_command_revival_operations(owner_uuid, profile_id)
                WHERE state IN ('PREPARED','RESERVED','COST_CONSUMED','APPLYING',
                    'REFUND_REQUIRED','QUARANTINED')
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS paid_command_revival_costs (
                    operation_id TEXT NOT NULL REFERENCES paid_command_revival_operations(operation_id)
                        ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL,
                    item_id TEXT NOT NULL,
                    quantity INTEGER NOT NULL CHECK (quantity > 0),
                    PRIMARY KEY (operation_id, ordinal),
                    UNIQUE (operation_id, item_id)
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS paid_command_revival_reservations (
                    operation_id TEXT NOT NULL REFERENCES paid_command_revival_operations(operation_id)
                        ON DELETE CASCADE,
                    cost_ordinal INTEGER NOT NULL,
                    stack_ordinal INTEGER NOT NULL,
                    compartment_id TEXT NOT NULL,
                    slot_index INTEGER NOT NULL,
                    quantity INTEGER NOT NULL CHECK (quantity > 0),
                    source_stack_fingerprint TEXT NOT NULL,
                    reservation_generation INTEGER NOT NULL CHECK (reservation_generation >= 0),
                    state TEXT NOT NULL CHECK (state IN (
                        'HELD','CONSUMED','RELEASED','REFUND_REQUIRED','REFUNDED')),
                    PRIMARY KEY (operation_id, cost_ordinal, stack_ordinal),
                    FOREIGN KEY (operation_id, cost_ordinal)
                        REFERENCES paid_command_revival_costs(operation_id, ordinal) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS paid_command_revival_refund_claims (
                    operation_id TEXT PRIMARY KEY REFERENCES paid_command_revival_operations(operation_id)
                        ON DELETE CASCADE,
                    owner_uuid TEXT NOT NULL,
                    exact_cost_json TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN (
                        'PENDING','DELIVERING','DELIVERED','QUARANTINED')),
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS paid_command_revival_apply_plans (
                    operation_id TEXT PRIMARY KEY REFERENCES paid_command_revival_operations(operation_id)
                        ON DELETE CASCADE,
                    projection_npc_uuid TEXT NOT NULL,
                    summon_session_id TEXT,
                    summon_remaining_ms INTEGER CHECK (
                        summon_remaining_ms IS NULL OR summon_remaining_ms >= 0),
                    summon_config_id TEXT,
                    summon_config_revision INTEGER CHECK (
                        summon_config_revision IS NULL OR summon_config_revision >= 0),
                    summon_policy_json TEXT,
                    created_at_ms INTEGER NOT NULL,
                    CHECK ((summon_session_id IS NULL AND summon_policy_json IS NULL)
                        OR (summon_session_id IS NOT NULL AND summon_policy_json IS NOT NULL))
                )
                """);
    }
}
