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
) VALUES
    (
        '20000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001',
        'Active Ω',
        'Tamework_Active',
        '{"mood":"ready"}',
        'state-active',
        'world-a',
        -62135596800000,
        -5000,
        -4000
    ),
    (
        '20000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-000000000002',
        '10000000-0000-0000-0000-000000000001',
        'Captured',
        'Tamework_Captured',
        '{"mood":"stored"}',
        'state-captured',
        'world-a',
        100,
        200,
        300
    ),
    (
        '20000000-0000-0000-0000-000000000003',
        '00000000-0000-0000-0000-000000000003',
        '10000000-0000-0000-0000-000000000001',
        'Dead',
        'Tamework_Dead',
        '{"mood":"down"}',
        'state-dead',
        'world-a',
        110,
        210,
        310
    ),
    (
        '20000000-0000-0000-0000-000000000004',
        '00000000-0000-0000-0000-000000000004',
        '10000000-0000-0000-0000-000000000001',
        'Lost',
        'Tamework_Lost',
        '{"mood":"missing"}',
        'state-lost',
        'world-b',
        120,
        220,
        320
    ),
    (
        '20000000-0000-0000-0000-000000000005',
        '00000000-0000-0000-0000-000000000005',
        '10000000-0000-0000-0000-000000000001',
        'Cooped',
        'Tamework_Coop',
        '{"mood":"resting"}',
        'state-coop',
        'world-a',
        130,
        230,
        330
    ),
    (
        '20000000-0000-0000-0000-000000000006',
        '00000000-0000-0000-0000-000000000006',
        '10000000-0000-0000-0000-000000000002',
        'Extension Data',
        'Tamework_Extension',
        '{"mood":"extended"}',
        'state-extension',
        'world-c',
        140,
        240,
        340
    );

INSERT INTO npc_uuid_aliases(npc_uuid, profile_id, is_current, mapped_at_ms) VALUES
    (
        '00000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000001',
        1,
        -5000
    ),
    (
        '00000000-0000-0000-0000-000000000011',
        '20000000-0000-0000-0000-000000000001',
        0,
        -6000
    ),
    (
        '00000000-0000-0000-0000-000000000002',
        '20000000-0000-0000-0000-000000000002',
        1,
        200
    ),
    (
        '00000000-0000-0000-0000-000000000003',
        '20000000-0000-0000-0000-000000000003',
        1,
        210
    ),
    (
        '00000000-0000-0000-0000-000000000004',
        '20000000-0000-0000-0000-000000000004',
        1,
        220
    ),
    (
        '00000000-0000-0000-0000-000000000005',
        '20000000-0000-0000-0000-000000000005',
        1,
        230
    ),
    (
        '00000000-0000-0000-0000-000000000006',
        '20000000-0000-0000-0000-000000000006',
        1,
        240
    );

INSERT INTO npc_tool_links(
    profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms
) VALUES
    (
        '20000000-0000-0000-0000-000000000002',
        '30000000-0000-0000-0000-000000000002',
        'capture',
        200,
        200
    ),
    (
        '20000000-0000-0000-0000-000000000003',
        '30000000-0000-0000-0000-000000000003',
        'death',
        210,
        210
    );

INSERT INTO npc_snapshots(
    profile_id, snapshot_type, snapshot_version, payload_json, is_active, created_at_ms
) VALUES
    (
        '20000000-0000-0000-0000-000000000002',
        'capture',
        1,
        '{"roleId":"Tamework_Captured","displayName":"Captured","capturedAtMs":250,"lastKnownPosition":{"x":1.0,"y":64.0,"z":2.0}}',
        1,
        250
    ),
    (
        '20000000-0000-0000-0000-000000000003',
        'death',
        1,
        '{"ownerId":"10000000-0000-0000-0000-000000000001","roleId":"Tamework_Dead","displayName":"Dead","diedAtMs":260,"respawnAvailableAtMs":-1000,"breedingCooldownUntilMs":-2000}',
        1,
        260
    ),
    (
        '20000000-0000-0000-0000-000000000004',
        'lost',
        1,
        '{"sourceNpcUuid":"00000000-0000-0000-0000-000000000004","lostAtMs":270,"lastRelocationQueuedAtMs":0,"relocationRetryAttempts":2}',
        1,
        270
    );

INSERT INTO coop_slots(
    world_name, coop_id, x, y, z, resident_slot, profile_id, housed_npc_uuid,
    last_released_npc_uuid, captured_at_ms, released_at_ms, updated_at_ms,
    state_snapshot_json
) VALUES (
    'world-a',
    'fixture-coop',
    10,
    64,
    20,
    0,
    '20000000-0000-0000-0000-000000000005',
    '00000000-0000-0000-0000-000000000005',
    NULL,
    280,
    0,
    280,
    '{"roleId":"Tamework_Coop","displayName":"Cooped","storedAtMs":280}'
);

INSERT INTO profile_states(
    profile_id, capture_active, death_active, lost_active, in_coop, coop_key, updated_at_ms
) VALUES
    ('20000000-0000-0000-0000-000000000001', 0, 0, 0, 0, NULL, -5000),
    ('20000000-0000-0000-0000-000000000002', 1, 0, 0, 0, NULL, 250),
    ('20000000-0000-0000-0000-000000000003', 0, 1, 0, 0, NULL, 260),
    ('20000000-0000-0000-0000-000000000004', 0, 0, 1, 0, NULL, 270),
    (
        '20000000-0000-0000-0000-000000000005',
        0,
        0,
        0,
        1,
        'world-a|fixture-coop|10|64|20|0',
        280
    ),
    ('20000000-0000-0000-0000-000000000006', 0, 0, 0, 0, NULL, 240);

INSERT INTO api_profile_data(
    profile_id, namespace, data_key, json_payload, created_at_ms, updated_at_ms
) VALUES (
    '20000000-0000-0000-0000-000000000006',
    'fixture',
    'unicode',
    '{"message":"preserve Ω and negative time","worldTimeMs":-3000}',
    240,
    245
);
