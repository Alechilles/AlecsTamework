-- Rows for bonded-companions.sqlite, including its schema history row.
-- Statements end with a semicolon at the end of a line, and no value contains a semicolon.

INSERT INTO bonded_schema_history (version, lineage, applied_at_ms, schema_hash)
VALUES (1, 'bonded-companions', 1, lower(hex(zeroblob(32))));

INSERT INTO bonded_companion_profile (profile_id, owner_uuid, roster_id, family_id, role_id, state, revision,
    snapshot_json, created_at_ms, updated_at_ms, policy_json, display_name, species, gender, died_at_ms,
    summon_cooldown_until_ms, revive_count, quarantine_reason, quarantined_at_ms)
VALUES ('b-active', '11111111-1111-1111-1111-111111111111', 'dragons', 'fire', 'Bonded_Dragon', 'ACTIVE', 6,
    '{"encoding":"base64","payload":"e30="}', 100, 600, '{"maxActive":1}', 'Ember', 'dragon', 'FEMALE', NULL,
    0, 0, NULL, NULL);
INSERT INTO bonded_companion_profile (profile_id, owner_uuid, roster_id, family_id, role_id, state, revision,
    snapshot_json, created_at_ms, updated_at_ms, policy_json, display_name, species, gender, died_at_ms,
    summon_cooldown_until_ms, revive_count, quarantine_reason, quarantined_at_ms)
VALUES ('b-dead', '11111111-1111-1111-1111-111111111111', 'dragons', 'frost', 'Bonded_Dragon', 'DEAD', 9,
    '{"encoding":"base64","payload":"e30="}', 200, 700, '{}', NULL, NULL, NULL, -650,
    -400, 2, 'damaged', 690);

INSERT INTO bonded_companion_lease (profile_id, lease_token, live_npc_uuid, world_key, started_at_ms,
    expires_at_ms, projection_state)
VALUES ('b-active', 'lease-1', 'cccccccc-0000-0000-0000-000000000001', 'world-a', 550, 0, 'LIVE');

INSERT INTO bonded_companion_extension_data (profile_id, namespace, json_payload, revision, updated_at_ms)
VALUES ('b-active', 'Alechilles:HyDragon', '{"encoding":"base64","payload":"eyJhIjoxfQ=="}', 1952, 600);

INSERT INTO bonded_companion_cleanup (cleanup_id, owner_uuid, roster_id, profile_id, lease_token, target_kind,
    target_npc_uuid, cleanup_reason, cleanup_state, attempt_count, next_attempt_at_ms, created_at_ms,
    retained_until_ms, world_key)
VALUES ('cleanup-1', '11111111-1111-1111-1111-111111111111', 'dragons', 'b-dead', NULL, 'PROJECTION',
    'cccccccc-0000-0000-0000-000000000002', 'death', 'PENDING', 3, 800, 700, 9000, 'world-a');

INSERT INTO bonded_companion_capture_source (profile_id, owner_uuid, roster_id, source_npc_uuid, source_world_key,
    caller_namespace, idempotency_key, request_hash, capture_evidence_json, capture_snapshot_json, committed_at_ms,
    event_published_at_ms)
VALUES ('b-active', '11111111-1111-1111-1111-111111111111', 'dragons', 'cccccccc-0000-0000-0000-000000000003',
    'world-b', 'Alechilles:HyDragon', 'capture-1', lower(hex(zeroblob(32))), '{}', '{}', 100, NULL);
