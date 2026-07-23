CREATE TABLE schema_history (
    version INTEGER PRIMARY KEY CHECK (version = 1),
    lineage TEXT NOT NULL CHECK (lineage = 'tamework-state'),
    applied_at_ms INTEGER NOT NULL,
    schema_hash TEXT NOT NULL CHECK (length(schema_hash) = 64)
);

CREATE TABLE companion_profile (
    profile_id TEXT PRIMARY KEY,
    display_name TEXT,
    role_id TEXT,
    metadata_json TEXT CHECK (metadata_json IS NULL OR json_valid(metadata_json)),
    metadata_hash TEXT CHECK (
        (metadata_json IS NULL AND metadata_hash IS NULL)
        OR (metadata_json IS NOT NULL AND length(metadata_hash) = 64)
    ),
    last_known_world_key TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    last_active_at_ms INTEGER NOT NULL,
    metadata_revision INTEGER NOT NULL CHECK (metadata_revision >= 0)
);

CREATE TABLE operation_envelope (
    operation_id TEXT PRIMARY KEY,
    idempotency_key TEXT NOT NULL,
    operation_kind TEXT NOT NULL,
    payload_version INTEGER NOT NULL CHECK (payload_version > 0),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    phase TEXT NOT NULL CHECK (phase IN (
        'PREPARED', 'LIVE_APPLYING', 'DURABLE', 'PUBLISHED',
        'COMPENSATING', 'COMPENSATED', 'RETRYABLE', 'FAILED', 'UNKNOWN'
    )),
    feature_scope TEXT NOT NULL,
    expected_lifecycle_revision INTEGER CHECK (
        expected_lifecycle_revision IS NULL OR expected_lifecycle_revision >= 0
    ),
    lease_owner TEXT,
    lease_until_ms INTEGER NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    failure_kind TEXT,
    failure_code TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    durable_at_ms INTEGER,
    published_at_ms INTEGER,
    terminal_at_ms INTEGER,
    UNIQUE (operation_kind, idempotency_key)
);

CREATE TABLE persistence_incident (
    incident_id TEXT PRIMARY KEY,
    failure_kind TEXT NOT NULL,
    failure_code TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'RESOLVED')),
    summary TEXT NOT NULL,
    evidence_json TEXT NOT NULL CHECK (json_valid(evidence_json)),
    created_at_ms INTEGER NOT NULL,
    resolved_at_ms INTEGER
);

CREATE TABLE persistence_quarantine (
    scope_type TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    incident_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'RELEASED')),
    reason_code TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    released_at_ms INTEGER,
    PRIMARY KEY (scope_type, scope_key),
    FOREIGN KEY (incident_id) REFERENCES persistence_incident(incident_id)
);

CREATE TABLE companion_alias (
    npc_uuid TEXT PRIMARY KEY,
    profile_id TEXT NOT NULL,
    alias_generation INTEGER NOT NULL CHECK (alias_generation >= 0),
    alias_state TEXT NOT NULL CHECK (alias_state IN ('LEASED', 'CURRENT', 'RETIRED')),
    lease_operation_id TEXT,
    mapped_at_ms INTEGER NOT NULL,
    retired_at_ms INTEGER,
    FOREIGN KEY (profile_id) REFERENCES companion_profile(profile_id) ON DELETE CASCADE,
    FOREIGN KEY (lease_operation_id) REFERENCES operation_envelope(operation_id),
    CHECK (
        (alias_state = 'LEASED' AND lease_operation_id IS NOT NULL AND retired_at_ms IS NULL)
        OR (alias_state = 'CURRENT' AND retired_at_ms IS NULL)
        OR (alias_state = 'RETIRED' AND retired_at_ms IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_companion_alias_current_profile
    ON companion_alias(profile_id) WHERE alias_state = 'CURRENT';
CREATE INDEX idx_companion_alias_profile_generation
    ON companion_alias(profile_id, alias_generation);

CREATE TABLE companion_lifecycle (
    profile_id TEXT PRIMARY KEY,
    owner_uuid TEXT,
    lifecycle_state TEXT NOT NULL CHECK (lifecycle_state IN (
        'ACTIVE', 'UNLOADED', 'CAPTURED', 'COOP', 'DEAD_REVIVABLE',
        'LOST', 'ROSTER_STORED', 'PROVISIONED_DORMANT', 'RELEASED', 'UNRESOLVED'
    )),
    location_kind TEXT NOT NULL CHECK (location_kind IN (
        'LIVE_ENTITY', 'CAPTURE_ITEM', 'COOP_SLOT', 'COMMAND_ROSTER',
        'PROVISIONING', 'NONE', 'UNRESOLVED'
    )),
    location_key TEXT,
    world_key TEXT,
    revision INTEGER NOT NULL CHECK (revision >= 0),
    active_operation_id TEXT,
    state_changed_at_ms INTEGER NOT NULL,
    last_reconciled_generation INTEGER NOT NULL CHECK (last_reconciled_generation >= 0),
    quarantine_incident_id TEXT,
    FOREIGN KEY (profile_id) REFERENCES companion_profile(profile_id) ON DELETE CASCADE,
    FOREIGN KEY (active_operation_id) REFERENCES operation_envelope(operation_id),
    FOREIGN KEY (quarantine_incident_id) REFERENCES persistence_incident(incident_id),
    CHECK (
        (lifecycle_state = 'ACTIVE'
            AND location_kind = 'LIVE_ENTITY'
            AND location_key IS NOT NULL
            AND world_key IS NOT NULL)
        OR (lifecycle_state IN ('UNLOADED', 'DEAD_REVIVABLE', 'LOST', 'RELEASED')
            AND location_kind = 'NONE'
            AND location_key IS NULL
            AND world_key IS NULL)
        OR (lifecycle_state = 'CAPTURED'
            AND location_kind = 'CAPTURE_ITEM'
            AND location_key IS NOT NULL
            AND world_key IS NULL)
        OR (lifecycle_state = 'COOP'
            AND location_kind = 'COOP_SLOT'
            AND location_key IS NOT NULL
            AND world_key IS NULL)
        OR (lifecycle_state = 'ROSTER_STORED'
            AND location_kind = 'COMMAND_ROSTER'
            AND location_key IS NOT NULL
            AND world_key IS NULL)
        OR (lifecycle_state = 'PROVISIONED_DORMANT'
            AND location_kind = 'PROVISIONING'
            AND location_key IS NOT NULL
            AND world_key IS NULL)
        OR (lifecycle_state = 'UNRESOLVED'
            AND location_kind = 'UNRESOLVED'
            AND location_key IS NULL
            AND world_key IS NULL)
    )
);

CREATE INDEX idx_companion_lifecycle_owner_state
    ON companion_lifecycle(owner_uuid, lifecycle_state);
CREATE INDEX idx_companion_lifecycle_location
    ON companion_lifecycle(location_kind, location_key);
CREATE INDEX idx_companion_lifecycle_active_operation
    ON companion_lifecycle(active_operation_id);

CREATE TABLE companion_snapshot (
    snapshot_id TEXT PRIMARY KEY,
    profile_id TEXT NOT NULL,
    snapshot_kind TEXT NOT NULL,
    payload_version INTEGER NOT NULL CHECK (payload_version > 0),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    payload_hash TEXT NOT NULL CHECK (length(payload_hash) = 64),
    source_lifecycle_revision INTEGER NOT NULL CHECK (source_lifecycle_revision >= 0),
    is_current INTEGER NOT NULL CHECK (is_current IN (0, 1)),
    created_at_ms INTEGER NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES companion_profile(profile_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_companion_snapshot_current_kind
    ON companion_snapshot(profile_id, snapshot_kind) WHERE is_current = 1;
CREATE INDEX idx_companion_snapshot_profile_created
    ON companion_snapshot(profile_id, created_at_ms);

CREATE TABLE companion_tool_link (
    profile_id TEXT NOT NULL,
    tool_uuid TEXT NOT NULL,
    link_type TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY (profile_id, tool_uuid, link_type),
    FOREIGN KEY (profile_id) REFERENCES companion_profile(profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_companion_tool_link_tool
    ON companion_tool_link(tool_uuid);

CREATE TABLE profile_extension_data (
    profile_id TEXT NOT NULL,
    namespace TEXT NOT NULL,
    data_key TEXT NOT NULL,
    payload_version INTEGER NOT NULL CHECK (payload_version > 0),
    json_payload TEXT NOT NULL CHECK (json_valid(json_payload)),
    payload_hash TEXT NOT NULL CHECK (length(payload_hash) = 64),
    revision INTEGER NOT NULL CHECK (revision > 0),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    deleted_at_ms INTEGER,
    PRIMARY KEY (profile_id, namespace, data_key),
    FOREIGN KEY (profile_id) REFERENCES companion_profile(profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_profile_extension_namespace
    ON profile_extension_data(profile_id, namespace);

CREATE TABLE operation_participant (
    operation_id TEXT NOT NULL,
    scope_type TEXT NOT NULL CHECK (scope_type IN (
        'OPERATION', 'PROFILE', 'OWNER', 'COOP', 'TOOL',
        'COMMAND_FAMILY', 'FEATURE', 'GLOBAL'
    )),
    scope_key TEXT NOT NULL,
    PRIMARY KEY (operation_id, scope_type, scope_key),
    FOREIGN KEY (operation_id) REFERENCES operation_envelope(operation_id) ON DELETE CASCADE
);

CREATE INDEX idx_operation_participant_scope
    ON operation_participant(scope_type, scope_key, operation_id);

CREATE TABLE refund_claim (
    operation_id TEXT PRIMARY KEY,
    recipient_uuid TEXT NOT NULL,
    item_id TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    reason_code TEXT NOT NULL,
    receipt_key TEXT NOT NULL UNIQUE,
    claimed_at_ms INTEGER NOT NULL,
    delivery_evidence TEXT,
    delivered_at_ms INTEGER,
    FOREIGN KEY (operation_id) REFERENCES operation_envelope(operation_id) ON DELETE CASCADE,
    CHECK (
        (delivery_evidence IS NULL AND delivered_at_ms IS NULL)
        OR (delivery_evidence IS NOT NULL AND delivered_at_ms IS NOT NULL)
    )
);

CREATE TABLE projection_outbox (
    event_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    aggregate_revision INTEGER NOT NULL CHECK (aggregate_revision >= 0),
    payload_version INTEGER NOT NULL CHECK (payload_version > 0),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    created_at_ms INTEGER NOT NULL,
    FOREIGN KEY (operation_id) REFERENCES operation_envelope(operation_id),
    UNIQUE (operation_id, event_type, aggregate_id, aggregate_revision)
);

CREATE INDEX idx_projection_outbox_aggregate
    ON projection_outbox(aggregate_id, aggregate_revision);

CREATE TABLE projection_checkpoint (
    consumer_id TEXT PRIMARY KEY,
    acknowledged_sequence INTEGER NOT NULL CHECK (acknowledged_sequence >= 0),
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE feature_circuit (
    feature_id TEXT PRIMARY KEY,
    state TEXT NOT NULL CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    failure_count INTEGER NOT NULL CHECK (failure_count >= 0),
    reason_code TEXT,
    opened_at_ms INTEGER,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE coop_slot (
    coop_key TEXT PRIMARY KEY,
    world_key TEXT NOT NULL,
    coop_id TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    resident_slot INTEGER NOT NULL CHECK (resident_slot >= 0),
    UNIQUE (world_key, coop_id, x, y, z, resident_slot)
);

CREATE TABLE coop_residency (
    coop_key TEXT PRIMARY KEY,
    profile_id TEXT NOT NULL UNIQUE,
    housed_npc_uuid TEXT,
    snapshot_id TEXT NOT NULL,
    captured_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    FOREIGN KEY (coop_key) REFERENCES coop_slot(coop_key) ON DELETE CASCADE,
    FOREIGN KEY (profile_id) REFERENCES companion_profile(profile_id) ON DELETE CASCADE,
    FOREIGN KEY (snapshot_id) REFERENCES companion_snapshot(snapshot_id)
);

CREATE TABLE import_manifest (
    import_id TEXT PRIMARY KEY,
    source_sha256 TEXT NOT NULL CHECK (length(source_sha256) = 64),
    source_schema_version INTEGER NOT NULL CHECK (source_schema_version BETWEEN 2 AND 4),
    importer_version INTEGER NOT NULL CHECK (importer_version > 0),
    source_snapshot_name TEXT NOT NULL,
    counts_json TEXT NOT NULL CHECK (json_valid(counts_json)),
    completed_at_ms INTEGER NOT NULL,
    UNIQUE (source_sha256, source_schema_version, importer_version)
);
