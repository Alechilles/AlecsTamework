package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.Statement;
import javax.annotation.Nonnull;

/** Adds capture, bonded-vessel, population-group, and provisioning durability authorities. */
final class SqliteSchemaV8Migration {

    void apply(@Nonnull Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            createCaptureAttempts(statement);
            createBondedVessels(statement);
            createPopulationGroups(statement);
            createProvisioningOperations(statement);
        }
    }

    private void createCaptureAttempts(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS capture_attempts (
                    attempt_id TEXT PRIMARY KEY,
                    caller_namespace TEXT,
                    idempotency_key TEXT,
                    actor_uuid TEXT NOT NULL,
                    target_npc_uuid TEXT NOT NULL,
                    profile_id TEXT,
                    expected_profile_revision INTEGER CHECK (expected_profile_revision IS NULL OR expected_profile_revision >= 0),
                    source_item_id TEXT NOT NULL,
                    source_role_id TEXT,
                    source_context_json TEXT NOT NULL,
                    spawner_config_id TEXT NOT NULL,
                    spawner_config_revision INTEGER NOT NULL CHECK (spawner_config_revision >= 0),
                    target_policy_config_id TEXT,
                    target_policy_config_revision INTEGER CHECK (target_policy_config_revision IS NULL OR target_policy_config_revision >= 0),
                    target_policy_bypassed INTEGER NOT NULL CHECK (target_policy_bypassed IN (0, 1)),
                    state TEXT NOT NULL CHECK (state IN (
                        'PREPARED', 'RESOLVED_FAILURE', 'RESOLVED_SUCCESS', 'APPLYING',
                        'COMMITTED', 'CANCELED', 'COMPENSATING', 'QUARANTINED')),
                    population_operation_id TEXT,
                    capture_operation_id TEXT,
                    power REAL,
                    minimum_power REAL,
                    current_health REAL,
                    maximum_health REAL,
                    missing_health_fraction REAL,
                    condition_bonus REAL,
                    effective_chance REAL,
                    entropy_sample REAL,
                    guaranteed INTEGER NOT NULL DEFAULT 0 CHECK (guaranteed IN (0, 1)),
                    outcome TEXT,
                    reason_code TEXT,
                    failure_cooldown_until_ms INTEGER NOT NULL DEFAULT 0,
                    event_emitted_at_ms INTEGER NOT NULL DEFAULT 0,
                    recovery_status TEXT NOT NULL DEFAULT 'NONE',
                    expires_at_ms INTEGER NOT NULL,
                    resolved_at_ms INTEGER NOT NULL DEFAULT 0,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    completed_at_ms INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    CHECK ((caller_namespace IS NULL AND idempotency_key IS NULL)
                        OR (caller_namespace IS NOT NULL AND length(caller_namespace) > 0
                            AND idempotency_key IS NOT NULL AND length(idempotency_key) > 0)),
                    CHECK (entropy_sample IS NULL OR (entropy_sample >= 0.0 AND entropy_sample < 1.0)),
                    CHECK (effective_chance IS NULL OR (effective_chance >= 0.0 AND effective_chance <= 1.0)),
                    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE SET NULL
                )
                """);
        statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_capture_attempts_caller_key
                ON capture_attempts(caller_namespace, idempotency_key)
                WHERE caller_namespace IS NOT NULL
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_capture_attempts_state_age
                ON capture_attempts(state, updated_at_ms, attempt_id)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_capture_attempts_target
                ON capture_attempts(target_npc_uuid, state)
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS capture_failure_cooldowns (
                    actor_uuid TEXT NOT NULL,
                    spawner_config_id TEXT NOT NULL,
                    attempt_id TEXT NOT NULL,
                    cooldown_until_ms INTEGER NOT NULL,
                    generation INTEGER NOT NULL CHECK (generation > 0),
                    updated_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (actor_uuid, spawner_config_id),
                    FOREIGN KEY (attempt_id) REFERENCES capture_attempts(attempt_id) ON DELETE RESTRICT
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_capture_failure_cooldowns_expiry
                ON capture_failure_cooldowns(cooldown_until_ms, actor_uuid)
                """);
    }

    private void createBondedVessels(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS bonded_vessel_bindings (
                    binding_id TEXT PRIMARY KEY,
                    profile_id TEXT NOT NULL,
                    generation INTEGER NOT NULL CHECK (generation > 0),
                    config_id TEXT NOT NULL,
                    config_revision INTEGER NOT NULL CHECK (config_revision >= 0),
                    lifecycle_state TEXT NOT NULL CHECK (lifecycle_state IN (
                        'STORED', 'SUMMONING', 'ACTIVE', 'STORING', 'DEAD',
                        'LOST', 'RELEASING', 'RELEASED')),
                    item_projection_status TEXT NOT NULL CHECK (item_projection_status IN (
                        'PRESENT', 'MISSING', 'AMBIGUOUS', 'REISSUE_PENDING', 'QUARANTINED')),
                    owner_uuid TEXT NOT NULL,
                    expected_profile_revision INTEGER NOT NULL CHECK (expected_profile_revision >= 0),
                    active_npc_uuid TEXT,
                    active_world_name TEXT,
                    active_chunk_x INTEGER,
                    active_chunk_z INTEGER,
                    cooldown_until_ms INTEGER NOT NULL DEFAULT 0,
                    last_item_id TEXT,
                    item_evidence_json TEXT,
                    active_operation_id TEXT,
                    diagnostic_reason TEXT,
                    row_revision INTEGER NOT NULL DEFAULT 0 CHECK (row_revision >= 0),
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    released_at_ms INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE RESTRICT,
                    CHECK ((active_world_name IS NULL AND active_chunk_x IS NULL AND active_chunk_z IS NULL)
                        OR (active_world_name IS NOT NULL AND active_chunk_x IS NOT NULL AND active_chunk_z IS NOT NULL))
                )
                """);
        statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_bonded_vessel_active_profile
                ON bonded_vessel_bindings(profile_id)
                WHERE lifecycle_state <> 'RELEASED'
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_bonded_vessel_owner_state
                ON bonded_vessel_bindings(owner_uuid, lifecycle_state)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_bonded_vessel_active_npc
                ON bonded_vessel_bindings(active_npc_uuid)
                WHERE active_npc_uuid IS NOT NULL
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS bonded_vessel_operations (
                    operation_id TEXT PRIMARY KEY,
                    caller_namespace TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    correlation_id TEXT,
                    binding_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    action TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN (
                        'PREPARED', 'APPLYING', 'APPLIED', 'COMMITTED', 'CANCELED',
                        'COMPENSATING', 'QUARANTINED', 'TERMINAL_DENIED')),
                    prior_generation INTEGER NOT NULL CHECK (prior_generation >= 0),
                    candidate_generation INTEGER NOT NULL CHECK (candidate_generation = prior_generation + 1),
                    expected_profile_revision INTEGER NOT NULL CHECK (expected_profile_revision >= 0),
                    config_id TEXT NOT NULL,
                    config_revision INTEGER NOT NULL CHECK (config_revision >= 0),
                    prior_lifecycle_state TEXT NOT NULL,
                    applying_lifecycle_state TEXT NOT NULL,
                    target_lifecycle_state TEXT NOT NULL,
                    prior_projection_status TEXT NOT NULL,
                    target_projection_status TEXT NOT NULL,
                    prior_cooldown_until_ms INTEGER NOT NULL,
                    target_cooldown_until_ms INTEGER NOT NULL,
                    source_item_id TEXT,
                    target_item_id TEXT,
                    source_fingerprint TEXT,
                    replacement_fingerprint TEXT,
                    source_context_json TEXT,
                    policy_snapshot_json TEXT NOT NULL,
                    population_operation_id TEXT,
                    actual_npc_uuid TEXT,
                    reason_code TEXT,
                    recovery_status TEXT NOT NULL DEFAULT 'NONE',
                    lease_expires_at_ms INTEGER NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    applied_at_ms INTEGER NOT NULL DEFAULT 0,
                    completed_at_ms INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (binding_id) REFERENCES bonded_vessel_bindings(binding_id) ON DELETE RESTRICT,
                    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE RESTRICT,
                    UNIQUE (caller_namespace, idempotency_key)
                )
                """);
        statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_bonded_vessel_nonterminal_generation
                ON bonded_vessel_operations(binding_id, prior_generation)
                WHERE state IN ('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING', 'QUARANTINED')
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_bonded_vessel_operations_recovery
                ON bonded_vessel_operations(state, updated_at_ms, operation_id)
                """);
    }

    private void createPopulationGroups(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS companion_population_group_classifications (
                    profile_id TEXT PRIMARY KEY,
                    role_id TEXT,
                    group_ids_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(group_ids_json)),
                    classification_revision INTEGER NOT NULL CHECK (classification_revision >= 0),
                    status TEXT NOT NULL CHECK (status IN ('RESOLVED', 'UNRESOLVED', 'OVER_CAP', 'QUARANTINED')),
                    source TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_population_group_classification_status
                ON companion_population_group_classifications(status, updated_at_ms, profile_id)
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS companion_population_group_assignments (
                    profile_id TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    role_id TEXT NOT NULL,
                    classification_revision INTEGER NOT NULL CHECK (classification_revision >= 0),
                    created_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (profile_id, group_id),
                    FOREIGN KEY (profile_id) REFERENCES companion_population_group_classifications(profile_id)
                        ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_population_group_assignments_group
                ON companion_population_group_assignments(group_id, profile_id)
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS companion_population_group_operations (
                    operation_id TEXT PRIMARY KEY,
                    population_operation_id TEXT,
                    profile_id TEXT NOT NULL,
                    operation_type TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN (
                        'PREPARED', 'APPLYING', 'APPLIED', 'COMMITTED', 'CANCELED',
                        'COMPENSATING', 'QUARANTINED', 'FAILED')),
                    expected_population_revision INTEGER NOT NULL CHECK (expected_population_revision >= 0),
                    classification_revision INTEGER NOT NULL CHECK (classification_revision >= 0),
                    old_owner_uuid TEXT,
                    new_owner_uuid TEXT,
                    old_role_id TEXT,
                    new_role_id TEXT,
                    old_group_ids_json TEXT NOT NULL CHECK (json_valid(old_group_ids_json)),
                    new_group_ids_json TEXT NOT NULL CHECK (json_valid(new_group_ids_json)),
                    old_lifecycle_state TEXT,
                    new_lifecycle_state TEXT,
                    old_ownership_world_name TEXT,
                    new_ownership_world_name TEXT,
                    reason_code TEXT,
                    recovery_status TEXT NOT NULL DEFAULT 'NONE',
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    completed_at_ms INTEGER NOT NULL DEFAULT 0
                )
                """);
        statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_population_group_nonterminal_profile
                ON companion_population_group_operations(profile_id)
                WHERE state IN ('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING', 'QUARANTINED')
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_population_group_operations_recovery
                ON companion_population_group_operations(state, updated_at_ms, operation_id)
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS companion_population_group_count_evidence (
                    operation_id TEXT NOT NULL,
                    owner_uuid TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    scope_kind TEXT NOT NULL CHECK (scope_kind IN ('GLOBAL', 'PER_WORLD')),
                    scope_world_name TEXT NOT NULL DEFAULT '',
                    committed_owned_before INTEGER NOT NULL CHECK (committed_owned_before >= 0),
                    committed_active_before INTEGER NOT NULL CHECK (committed_active_before >= 0),
                    pending_owned_before INTEGER NOT NULL CHECK (pending_owned_before >= 0),
                    pending_active_before INTEGER NOT NULL CHECK (pending_active_before >= 0),
                    owned_delta INTEGER NOT NULL,
                    active_delta INTEGER NOT NULL,
                    max_owned INTEGER NOT NULL CHECK (max_owned >= 0),
                    max_active INTEGER NOT NULL CHECK (max_active >= 0),
                    policy_revision INTEGER NOT NULL CHECK (policy_revision >= 0),
                    state TEXT NOT NULL CHECK (state IN ('RESERVED', 'APPLIED', 'RELEASED', 'QUARANTINED')),
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (operation_id, owner_uuid, group_id, scope_kind, scope_world_name),
                    FOREIGN KEY (operation_id) REFERENCES companion_population_group_operations(operation_id)
                        ON DELETE CASCADE,
                    CHECK ((scope_kind = 'GLOBAL' AND scope_world_name = '')
                        OR (scope_kind = 'PER_WORLD' AND length(scope_world_name) > 0))
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_population_group_count_scope
                ON companion_population_group_count_evidence(
                    owner_uuid, group_id, scope_kind, scope_world_name, state)
                """);
    }

    private void createProvisioningOperations(@Nonnull Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS companion_provisioning_operations (
                    operation_id TEXT PRIMARY KEY,
                    caller_namespace TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    correlation_id TEXT,
                    owner_uuid TEXT NOT NULL,
                    target_role_id TEXT NOT NULL,
                    requested_disposition TEXT NOT NULL CHECK (requested_disposition IN ('PROVISIONED_DORMANT', 'ACTIVE')),
                    ownership_world_name TEXT,
                    destination_context_json TEXT,
                    initial_profile_json TEXT,
                    expected_policy_revision INTEGER CHECK (expected_policy_revision IS NULL OR expected_policy_revision >= 0),
                    provisional_profile_id TEXT NOT NULL,
                    canonical_profile_id TEXT,
                    state TEXT NOT NULL CHECK (state IN (
                        'PREPARING_DORMANT', 'DORMANT_PREPARED', 'DORMANT_APPLYING',
                        'DORMANT_COMMITTED', 'ACTIVE_PREPARED', 'ACTIVE_APPLYING',
                        'COMMITTED', 'PARTIAL_DORMANT', 'DENIED', 'CANCELED', 'QUARANTINED')),
                    dormant_population_operation_id TEXT,
                    active_population_operation_id TEXT,
                    result_code TEXT,
                    projection_reason TEXT,
                    recovery_status TEXT NOT NULL DEFAULT 'NONE',
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    completed_at_ms INTEGER NOT NULL DEFAULT 0,
                    UNIQUE (caller_namespace, idempotency_key)
                )
                """);
        statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_companion_provisioning_profile
                ON companion_provisioning_operations(canonical_profile_id)
                WHERE canonical_profile_id IS NOT NULL
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_companion_provisioning_recovery
                ON companion_provisioning_operations(state, updated_at_ms, operation_id)
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_companion_provisioning_owner
                ON companion_provisioning_operations(owner_uuid, state)
                """);
    }
}
