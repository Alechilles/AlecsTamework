-- @include schema-public-v2.sql
-- @include schema-public-v3.sql
-- @include schema-public-v4.sql

INSERT INTO schema_migrations(version, name, applied_at_ms) VALUES
    (2, 'schema_v2', 1000),
    (3, 'schema_v3_api_profile_data', 1001),
    (4, 'schema_v4_coop_state_snapshot', 1002);

INSERT INTO npc_profiles(
    profile_id, current_npc_uuid, owner_uuid, display_name, role_id,
    state_json, state_hash, last_world_name, created_at_ms, updated_at_ms, last_active_at_ms
) VALUES (
    '21000000-0000-0000-0000-000000000001',
    '01000000-0000-0000-0000-000000000001',
    '11000000-0000-0000-0000-000000000001',
    'Conflicted',
    'Tamework_Conflict',
    '{}',
    'state-conflict',
    'world-conflict',
    100,
    200,
    200
);

INSERT INTO npc_uuid_aliases(npc_uuid, profile_id, is_current, mapped_at_ms) VALUES (
    '01000000-0000-0000-0000-000000000001',
    '21000000-0000-0000-0000-000000000001',
    1,
    200
);

INSERT INTO npc_snapshots(
    profile_id, snapshot_type, snapshot_version, payload_json, is_active, created_at_ms
) VALUES
    (
        '21000000-0000-0000-0000-000000000001',
        'capture',
        1,
        '{"capturedAtMs":210}',
        1,
        210
    ),
    (
        '21000000-0000-0000-0000-000000000001',
        'death',
        1,
        '{"diedAtMs":220}',
        1,
        220
    );

INSERT INTO profile_states(
    profile_id, capture_active, death_active, lost_active, in_coop, coop_key, updated_at_ms
) VALUES (
    '21000000-0000-0000-0000-000000000001',
    1,
    1,
    0,
    0,
    NULL,
    220
);
