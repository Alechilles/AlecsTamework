CREATE TABLE bonded_schema_history (
    version INTEGER PRIMARY KEY CHECK (version = 1),
    lineage TEXT NOT NULL CHECK (lineage = 'bonded-companions'),
    applied_at_ms INTEGER NOT NULL,
    schema_hash TEXT NOT NULL CHECK (length(schema_hash) = 64)
);

CREATE TABLE bonded_companion_profile (
    profile_id TEXT PRIMARY KEY CHECK (length(trim(profile_id)) > 0),
    owner_uuid TEXT NOT NULL CHECK (
        length(owner_uuid) = 36
        AND substr(owner_uuid, 9, 1) = '-'
        AND substr(owner_uuid, 14, 1) = '-'
        AND substr(owner_uuid, 19, 1) = '-'
        AND substr(owner_uuid, 24, 1) = '-'
        AND lower(owner_uuid) = owner_uuid
        AND replace(owner_uuid, '-', '') NOT GLOB '*[^0-9a-f]*'
    ),
    roster_id TEXT NOT NULL CHECK (length(trim(roster_id)) > 0),
    family_id TEXT NOT NULL CHECK (length(trim(family_id)) > 0),
    role_id TEXT NOT NULL CHECK (length(trim(role_id)) > 0),
    state TEXT NOT NULL CHECK (state IN ('STORED', 'ACTIVE', 'DEAD')),
    revision INTEGER NOT NULL CHECK (revision >= 0),
    snapshot_json TEXT NOT NULL CHECK (
        json_valid(snapshot_json)
        AND json_type(snapshot_json) = 'object'
        AND json_extract(snapshot_json, '$.encoding') = 'base64'
        AND json_type(snapshot_json, '$.payload') = 'text'
        AND length(json_extract(snapshot_json, '$.payload')) > 0
    ),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    policy_json TEXT NOT NULL CHECK (
        json_valid(policy_json) AND json_type(policy_json) = 'object'
    ),
    display_name TEXT,
    species TEXT,
    gender TEXT,
    died_at_ms INTEGER,
    revive_cooldown_until_ms INTEGER NOT NULL,
    revive_count INTEGER NOT NULL CHECK (revive_count >= 0),
    quarantine_reason TEXT,
    quarantined_at_ms INTEGER,
    UNIQUE(profile_id, owner_uuid, roster_id),
    CHECK ((quarantine_reason IS NULL) = (quarantined_at_ms IS NULL))
);

CREATE INDEX bonded_profile_owner_roster_idx
    ON bonded_companion_profile(owner_uuid, roster_id, profile_id);

CREATE TABLE bonded_companion_lease (
    profile_id TEXT PRIMARY KEY,
    lease_token TEXT NOT NULL UNIQUE CHECK (length(trim(lease_token)) > 0),
    live_npc_uuid TEXT NOT NULL UNIQUE CHECK (
        length(live_npc_uuid) = 36
        AND substr(live_npc_uuid, 9, 1) = '-'
        AND substr(live_npc_uuid, 14, 1) = '-'
        AND substr(live_npc_uuid, 19, 1) = '-'
        AND substr(live_npc_uuid, 24, 1) = '-'
        AND lower(live_npc_uuid) = live_npc_uuid
        AND replace(live_npc_uuid, '-', '') NOT GLOB '*[^0-9a-f]*'
    ),
    world_key TEXT NOT NULL CHECK (length(trim(world_key)) > 0),
    started_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    projection_state TEXT NOT NULL CHECK (
        projection_state IN ('PENDING', 'LIVE', 'REMOVE_PENDING')
    ),
    FOREIGN KEY(profile_id) REFERENCES bonded_companion_profile(profile_id)
        ON DELETE CASCADE,
    CHECK (expires_at_ms = 0 OR expires_at_ms >= started_at_ms)
);

CREATE INDEX bonded_lease_expiry_idx
    ON bonded_companion_lease(expires_at_ms, profile_id);

CREATE TABLE bonded_companion_extension_data (
    profile_id TEXT NOT NULL,
    namespace TEXT NOT NULL CHECK (length(trim(namespace)) > 0),
    json_payload TEXT NOT NULL CHECK (json_valid(json_payload)),
    revision INTEGER NOT NULL CHECK (revision >= 0),
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY(profile_id, namespace),
    FOREIGN KEY(profile_id) REFERENCES bonded_companion_profile(profile_id)
        ON DELETE CASCADE
);

CREATE TABLE bonded_companion_cleanup (
    cleanup_id TEXT PRIMARY KEY CHECK (length(trim(cleanup_id)) > 0),
    owner_uuid TEXT NOT NULL CHECK (length(owner_uuid) = 36),
    roster_id TEXT NOT NULL CHECK (length(trim(roster_id)) > 0),
    profile_id TEXT NOT NULL,
    lease_token TEXT,
    target_kind TEXT NOT NULL CHECK (target_kind IN ('SOURCE', 'PROJECTION')),
    target_npc_uuid TEXT NOT NULL CHECK (length(target_npc_uuid) = 36),
    cleanup_reason TEXT NOT NULL CHECK (length(trim(cleanup_reason)) > 0),
    cleanup_state TEXT NOT NULL CHECK (
        cleanup_state IN ('PENDING', 'COMPLETED', 'ABANDONED')
    ),
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0),
    next_attempt_at_ms INTEGER NOT NULL,
    created_at_ms INTEGER NOT NULL,
    retained_until_ms INTEGER NOT NULL CHECK (retained_until_ms <> 0),
    FOREIGN KEY(profile_id, owner_uuid, roster_id)
        REFERENCES bonded_companion_profile(profile_id, owner_uuid, roster_id)
        ON DELETE CASCADE
);

CREATE INDEX bonded_cleanup_due_idx
    ON bonded_companion_cleanup(cleanup_state, next_attempt_at_ms, cleanup_id);
CREATE INDEX bonded_cleanup_retention_idx
    ON bonded_companion_cleanup(cleanup_state, retained_until_ms, cleanup_id);

CREATE TABLE bonded_companion_operation (
    caller_namespace TEXT NOT NULL CHECK (length(trim(caller_namespace)) > 0),
    idempotency_key TEXT NOT NULL CHECK (length(trim(idempotency_key)) > 0),
    owner_uuid TEXT NOT NULL CHECK (length(owner_uuid) = 36),
    roster_id TEXT NOT NULL CHECK (length(trim(roster_id)) > 0),
    profile_id TEXT,
    operation_type TEXT NOT NULL CHECK (
        operation_type IN ('CAPTURE', 'PROVISION', 'SUMMON', 'STORE', 'REVIVE', 'CLEANUP')
    ),
    request_hash TEXT NOT NULL CHECK (
        length(request_hash) = 64 AND lower(request_hash) = request_hash
        AND request_hash NOT GLOB '*[^0-9a-f]*'
    ),
    operation_state TEXT NOT NULL CHECK (
        operation_state IN ('PENDING', 'SUCCEEDED', 'REJECTED', 'FAILED')
    ),
    result_json TEXT CHECK (result_json IS NULL OR json_valid(result_json)),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL CHECK (expires_at_ms <> 0),
    PRIMARY KEY(caller_namespace, idempotency_key)
);

CREATE INDEX bonded_operation_retention_idx
    ON bonded_companion_operation(expires_at_ms, caller_namespace, idempotency_key);
