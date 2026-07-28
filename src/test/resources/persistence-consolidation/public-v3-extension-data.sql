-- @include schema-public-v2.sql
-- @include schema-public-v3.sql

INSERT INTO schema_migrations(version, name, applied_at_ms) VALUES
    (2, 'schema_v2', 1000),
    (3, 'schema_v3_api_profile_data', 1001);

INSERT INTO npc_profiles(
    profile_id, current_npc_uuid, owner_uuid, display_name, role_id,
    state_json, state_hash, last_world_name, created_at_ms, updated_at_ms, last_active_at_ms
) VALUES (
    '20000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    'V3 Companion',
    'Tamework_Test',
    '{"version":3}',
    'state-v3',
    'world-v3',
    100,
    200,
    300
);

INSERT INTO npc_uuid_aliases(npc_uuid, profile_id, is_current, mapped_at_ms) VALUES (
    '00000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    1,
    200
);

INSERT INTO profile_states(
    profile_id, capture_active, death_active, lost_active, in_coop, coop_key, updated_at_ms
) VALUES (
    '20000000-0000-0000-0000-000000000001',
    0,
    0,
    0,
    0,
    NULL,
    200
);

INSERT INTO api_profile_data(
    profile_id, namespace, data_key, json_payload, created_at_ms, updated_at_ms
) VALUES (
    '20000000-0000-0000-0000-000000000001',
    'fixture',
    'v3-data',
    '{"preserved":true}',
    210,
    220
);
