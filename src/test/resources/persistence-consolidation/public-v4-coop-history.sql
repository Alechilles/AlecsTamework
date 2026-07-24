-- @include public-v4-representative.sql

-- Public v2.16.1 retains released slot history with profile and snapshot evidence.
-- Only housed_npc_uuid is positive current-residency evidence.
INSERT INTO coop_slots(
    world_name, coop_id, x, y, z, resident_slot, profile_id, housed_npc_uuid,
    last_released_npc_uuid, captured_at_ms, released_at_ms, updated_at_ms,
    state_snapshot_json
) VALUES
    (
        'world-a',
        'released-coop',
        30,
        64,
        40,
        0,
        '20000000-0000-0000-0000-000000000005',
        NULL,
        '00000000-0000-0000-0000-000000000005',
        180,
        220,
        220,
        '{"version":"1","history":"previous-slot-for-current-resident"}'
    ),
    (
        'world-a',
        'released-coop',
        30,
        64,
        40,
        1,
        '20000000-0000-0000-0000-000000000001',
        NULL,
        '00000000-0000-0000-0000-000000000001',
        181,
        221,
        221,
        '{"version":"1","history":"released-profile-no-longer-cooped"}'
    );
