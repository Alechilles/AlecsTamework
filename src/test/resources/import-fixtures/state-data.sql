-- Domain rows shared by the three state schema versions. No schema_history rows: the test adds those.
-- Statements end with a semicolon at the end of a line, and no value contains a semicolon.

INSERT INTO operation_envelope (operation_id, idempotency_key, operation_kind, payload_version, payload_json,
    phase, feature_scope, created_at_ms, updated_at_ms)
VALUES ('op-done', 'k-done', 'TAME', 1, '{}', 'PUBLISHED', 'companion', 100, 100);
INSERT INTO operation_envelope (operation_id, idempotency_key, operation_kind, payload_version, payload_json,
    phase, feature_scope, created_at_ms, updated_at_ms)
VALUES ('op-open', 'k-open', 'PROVISION', 1, '{}', 'PREPARED', 'companion', 200, 200);

INSERT INTO persistence_incident (incident_id, failure_kind, failure_code, state, summary, evidence_json, created_at_ms)
VALUES ('incident-1', 'INVARIANT', 'test', 'OPEN', 'fixture', '{}', 300);

INSERT INTO companion_profile (profile_id, display_name, role_id, metadata_json, metadata_hash,
    last_known_world_key, created_at_ms, updated_at_ms, last_active_at_ms, metadata_revision)
VALUES ('p-live', 'Rex', 'Tamed_Wolf', '{"owner_name":"Alec","custom_name":"Rex"}', hex(zeroblob(32)),
    'world-a', 1000, 1500, 1400, 3);
INSERT INTO companion_profile (profile_id, display_name, role_id, metadata_json, metadata_hash,
    last_known_world_key, created_at_ms, updated_at_ms, last_active_at_ms, metadata_revision)
VALUES ('p-dead', NULL, NULL, NULL, NULL, NULL, 2000, 2500, 2400, 0);
INSERT INTO companion_profile (profile_id, display_name, role_id, metadata_json, metadata_hash,
    last_known_world_key, created_at_ms, updated_at_ms, last_active_at_ms, metadata_revision)
VALUES ('p-coop', 'Hen', 'Tamed_Chicken', NULL, NULL, 'world-a', 3000, 3500, 3400, 0);
INSERT INTO companion_profile (profile_id, display_name, role_id, metadata_json, metadata_hash,
    last_known_world_key, created_at_ms, updated_at_ms, last_active_at_ms, metadata_revision)
VALUES ('p-prov', 'Drake', 'Tamed_Drake', NULL, NULL, NULL, 4000, 4500, 4400, 0);

INSERT INTO companion_lifecycle (profile_id, owner_uuid, lifecycle_state, location_kind, location_key, world_key,
    owner_world_key, revision, active_operation_id, state_changed_at_ms, last_reconciled_generation,
    quarantine_incident_id)
VALUES ('p-live', '11111111-1111-1111-1111-111111111111', 'ACTIVE', 'LIVE_ENTITY',
    'aaaaaaaa-0000-0000-0000-000000000002', 'world-a', 'world-home', 7, NULL, -5000, 0, NULL);
INSERT INTO companion_lifecycle (profile_id, owner_uuid, lifecycle_state, location_kind, location_key, world_key,
    owner_world_key, revision, active_operation_id, state_changed_at_ms, last_reconciled_generation,
    quarantine_incident_id)
VALUES ('p-dead', NULL, 'DEAD_REVIVABLE', 'NONE', NULL, NULL, NULL, 2, NULL, 2400, 0, NULL);
INSERT INTO companion_lifecycle (profile_id, owner_uuid, lifecycle_state, location_kind, location_key, world_key,
    owner_world_key, revision, active_operation_id, state_changed_at_ms, last_reconciled_generation,
    quarantine_incident_id)
VALUES ('p-coop', '11111111-1111-1111-1111-111111111111', 'COOP', 'COOP_SLOT', 'v1:d29ybGQtYQ:Y29vcA:10:64:-20:0',
    NULL, NULL, 4, NULL, 3400, 0, NULL);
INSERT INTO companion_lifecycle (profile_id, owner_uuid, lifecycle_state, location_kind, location_key, world_key,
    owner_world_key, revision, active_operation_id, state_changed_at_ms, last_reconciled_generation,
    quarantine_incident_id)
VALUES ('p-prov', '11111111-1111-1111-1111-111111111111', 'PROVISIONED_DORMANT', 'PROVISIONING', 'prov-key',
    NULL, NULL, 1, 'op-open', 4400, 0, 'incident-1');

INSERT INTO companion_alias (npc_uuid, profile_id, alias_generation, alias_state, lease_operation_id,
    mapped_at_ms, retired_at_ms)
VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'p-live', 0, 'RETIRED', NULL, 1000, 1200);
INSERT INTO companion_alias (npc_uuid, profile_id, alias_generation, alias_state, lease_operation_id,
    mapped_at_ms, retired_at_ms)
VALUES ('aaaaaaaa-0000-0000-0000-000000000002', 'p-live', 1, 'CURRENT', NULL, 1200, NULL);
INSERT INTO companion_alias (npc_uuid, profile_id, alias_generation, alias_state, lease_operation_id,
    mapped_at_ms, retired_at_ms)
VALUES ('aaaaaaaa-0000-0000-0000-000000000003', 'p-prov', 0, 'LEASED', 'op-open', 4400, NULL);

INSERT INTO companion_snapshot (snapshot_id, profile_id, snapshot_kind, payload_version, payload_json,
    payload_hash, source_lifecycle_revision, is_current, created_at_ms)
VALUES ('snap-dead-old', 'p-dead', 'death', 2, '{"old":true}', hex(zeroblob(32)), 1, 0, 2100);
INSERT INTO companion_snapshot (snapshot_id, profile_id, snapshot_kind, payload_version, payload_json,
    payload_hash, source_lifecycle_revision, is_current, created_at_ms)
VALUES ('snap-dead', 'p-dead', 'death', 2,
    '{"fullState":{"roleId":"Tamed_Wolf"},"diedAtMs":-900,"respawnAvailableAtMs":0,"deathCauseKind":"FALL"}',
    hex(zeroblob(32)), 2, 1, 2400);
INSERT INTO companion_snapshot (snapshot_id, profile_id, snapshot_kind, payload_version, payload_json,
    payload_hash, source_lifecycle_revision, is_current, created_at_ms)
VALUES ('snap-coop', 'p-coop', 'coop', 1, '{"roleId":"Tamed_Chicken"}', hex(zeroblob(32)), 4, 0, 3400);

INSERT INTO companion_tool_link (profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms)
VALUES ('p-live', 'bbbbbbbb-0000-0000-0000-000000000001', 'COMMAND', 1300, 1350);

INSERT INTO command_family (owner_uuid, family_id, roster_revision, created_at_ms, updated_at_ms)
VALUES ('11111111-1111-1111-1111-111111111111', 'wolves', 5, 1000, 1500);
INSERT INTO command_roster_membership (slot_id, profile_id, owner_uuid, family_id, membership_revision, group_id,
    active_for_bulk_commands, home_world_key, home_x, home_y, home_z, created_at_ms, updated_at_ms)
VALUES ('slot-1', 'p-live', '11111111-1111-1111-1111-111111111111', 'wolves', 2, 'pack', 1,
    'world-home', 1.5, 64.0, -2.25, 1000, 1500);
INSERT INTO command_roster_membership (slot_id, profile_id, owner_uuid, family_id, membership_revision, group_id,
    active_for_bulk_commands, home_world_key, home_x, home_y, home_z, created_at_ms, updated_at_ms)
VALUES ('slot-2', 'p-prov', '11111111-1111-1111-1111-111111111111', 'wolves', 1, NULL, 0,
    NULL, NULL, NULL, NULL, 4000, 4500);

INSERT INTO timed_summon_lease (profile_id, lease_revision, session_id, remaining_ms, cooldown_until_ms, config_id,
    config_revision, active_duration_ms, resummon_cooldown_ms, auto_store_on_owner_logout,
    warning_thresholds_json, emitted_warning_thresholds_json, checkpointed_at_ms, created_at_ms, updated_at_ms)
VALUES ('p-live', 3, 'session-1', 45000, NULL, 'Timed_Wolf', 1, 60000, 30000, 1, '[10000]', '[]', 1450, 1000, 1450);
INSERT INTO timed_summon_lease (profile_id, lease_revision, session_id, remaining_ms, cooldown_until_ms, config_id,
    config_revision, active_duration_ms, resummon_cooldown_ms, auto_store_on_owner_logout,
    warning_thresholds_json, emitted_warning_thresholds_json, checkpointed_at_ms, created_at_ms, updated_at_ms)
VALUES ('p-prov', 1, NULL, NULL, -7000, NULL, NULL, 60000, 30000, 0, '[]', '[]', NULL, 4000, 4500);

INSERT INTO coop_slot (coop_key, world_key, coop_id, x, y, z, resident_slot, residency_revision,
    active_operation_id, reserved_profile_id)
VALUES ('v1:d29ybGQtYQ:Y29vcA:10:64:-20:0', 'world-a', 'coop', 10, 64, -20, 0, 2, NULL, NULL);
INSERT INTO coop_slot (coop_key, world_key, coop_id, x, y, z, resident_slot, residency_revision,
    active_operation_id, reserved_profile_id)
VALUES ('v1:d29ybGQtYQ:Y29vcA:10:64:-20:1', 'world-a', 'coop', 10, 64, -20, 1, 0, NULL, NULL);
INSERT INTO coop_residency (coop_key, profile_id, housed_npc_uuid, snapshot_id, captured_at_ms, updated_at_ms)
VALUES ('v1:d29ybGQtYQ:Y29vcA:10:64:-20:0', 'p-coop', NULL, 'snap-coop', 3400, 3450);

INSERT INTO provisioning_record (profile_id, caller_namespace, caller_key, correlation_id, policy_revision,
    creation_operation_id, created_at_ms)
VALUES ('p-prov', 'Alechilles:HyDragon', 'drake-1', NULL, 0, 'op-open', 4000);

INSERT INTO profile_extension_data (profile_id, namespace, data_key, payload_version, json_payload, payload_hash,
    revision, created_at_ms, updated_at_ms, deleted_at_ms)
VALUES ('p-live', 'Alechilles:Tamework:EntityCheckpoint', 'alias:aaaaaaaa-0000-0000-0000-000000000002', 1,
    '{"profileId":"p-live","worldKey":"world-a","x":1.0,"y":2.0,"z":3.0,"capturedAtMs":1480}',
    hex(zeroblob(32)), 9, 1200, 1480, NULL);
INSERT INTO profile_extension_data (profile_id, namespace, data_key, payload_version, json_payload, payload_hash,
    revision, created_at_ms, updated_at_ms, deleted_at_ms)
VALUES ('p-live', 'Alechilles:Tamework:EntityCheckpoint', 'alias:aaaaaaaa-0000-0000-0000-000000000001', 1,
    '{"profileId":"p-live"}', hex(zeroblob(32)), 2, 1000, 1200, 1200);
INSERT INTO profile_extension_data (profile_id, namespace, data_key, payload_version, json_payload, payload_hash,
    revision, created_at_ms, updated_at_ms, deleted_at_ms)
VALUES ('p-live', 'Alechilles:Tamework:EntityCheckpoint', 'alias:aaaaaaaa-0000-0000-0000-000000000009', 1,
    '{"profileId":"p-live","capturedAtMs":1495}', hex(zeroblob(32)), 1, 1495, 1495, NULL);
INSERT INTO profile_extension_data (profile_id, namespace, data_key, payload_version, json_payload, payload_hash,
    revision, created_at_ms, updated_at_ms, deleted_at_ms)
VALUES ('p-dead', 'Alechilles:Tamework:EntityCheckpoint', 'alias:aaaaaaaa-0000-0000-0000-000000000008', 1,
    '{"profileId":"p-dead","capturedAtMs":2300}', hex(zeroblob(32)), 1, 2300, 2300, NULL);
INSERT INTO profile_extension_data (profile_id, namespace, data_key, payload_version, json_payload, payload_hash,
    revision, created_at_ms, updated_at_ms, deleted_at_ms)
VALUES ('p-live', 'Mod:Thing', 'k1', 1, '{"level":4}', hex(zeroblob(32)), 12, 1100, 1490, NULL);
INSERT INTO profile_extension_data (profile_id, namespace, data_key, payload_version, json_payload, payload_hash,
    revision, created_at_ms, updated_at_ms, deleted_at_ms)
VALUES ('p-live', 'Mod:Thing', 'gone', 1, '{}', hex(zeroblob(32)), 3, 1100, 1300, 1300);
