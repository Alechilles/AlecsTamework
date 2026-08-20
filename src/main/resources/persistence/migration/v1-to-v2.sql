-- Rebuild the history constraint without changing the preserved v1 row.
CREATE TABLE schema_history_v2 (
    version INTEGER PRIMARY KEY CHECK (version BETWEEN 1 AND 2),
    lineage TEXT NOT NULL CHECK (lineage = 'tamework-state'),
    applied_at_ms INTEGER NOT NULL,
    schema_hash TEXT NOT NULL CHECK (length(schema_hash) = 64)
);

INSERT INTO schema_history_v2(version, lineage, applied_at_ms, schema_hash)
SELECT version, lineage, applied_at_ms, schema_hash
FROM schema_history;

DROP TABLE schema_history;
CREATE TABLE schema_history (
    version INTEGER PRIMARY KEY CHECK (version BETWEEN 1 AND 2),
    lineage TEXT NOT NULL CHECK (lineage = 'tamework-state'),
    applied_at_ms INTEGER NOT NULL,
    schema_hash TEXT NOT NULL CHECK (length(schema_hash) = 64)
);

INSERT INTO schema_history(version, lineage, applied_at_ms, schema_hash)
SELECT version, lineage, applied_at_ms, schema_hash
FROM schema_history_v2;

DROP TABLE schema_history_v2;

CREATE TABLE population_domain_reservation (
    operation_id TEXT NOT NULL,
    profile_id TEXT NOT NULL,
    expected_lifecycle_revision INTEGER CHECK (
        expected_lifecycle_revision IS NULL
        OR expected_lifecycle_revision >= 0
    ),
    owner_uuid TEXT NOT NULL,
    domain_id TEXT NOT NULL,
    scope_kind TEXT NOT NULL CHECK (scope_kind IN ('GLOBAL', 'PER_WORLD')),
    owner_world_key TEXT NOT NULL,
    owned_delta INTEGER NOT NULL CHECK (owned_delta >= 0),
    deployable_delta INTEGER NOT NULL CHECK (deployable_delta >= 0),
    weight INTEGER NOT NULL CHECK (weight > 0),
    snapshotted_max_owned INTEGER NOT NULL CHECK (
        snapshotted_max_owned >= 0
    ),
    snapshotted_max_deployable INTEGER NOT NULL CHECK (
        snapshotted_max_deployable >= 0
    ),
    policy_revision INTEGER NOT NULL CHECK (policy_revision >= 0),
    created_at_ms INTEGER NOT NULL,
    PRIMARY KEY (
        operation_id, owner_uuid, domain_id, scope_kind, owner_world_key
    ),
    FOREIGN KEY (operation_id) REFERENCES operation_envelope(operation_id)
        ON DELETE CASCADE,
    CHECK (owned_delta > 0 OR deployable_delta > 0),
    CHECK (
        (scope_kind = 'GLOBAL' AND owner_world_key = '')
        OR (scope_kind = 'PER_WORLD'
            AND length(trim(owner_world_key)) > 0)
    )
);

CREATE INDEX idx_population_domain_reservation_scope
    ON population_domain_reservation(
        owner_uuid, domain_id, scope_kind, owner_world_key, operation_id
    );

CREATE TABLE companion_output_claim (
    profile_id TEXT NOT NULL,
    output_key TEXT NOT NULL,
    owner_uuid TEXT NOT NULL,
    claim_revision INTEGER NOT NULL CHECK (claim_revision > 0),
    active_operation_id TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY (profile_id, output_key),
    FOREIGN KEY (profile_id) REFERENCES companion_profile(profile_id)
        ON DELETE CASCADE,
    FOREIGN KEY (active_operation_id) REFERENCES operation_envelope(operation_id)
);

CREATE INDEX idx_companion_output_claim_owner
    ON companion_output_claim(owner_uuid, profile_id, output_key);

CREATE TABLE companion_output_claim_item (
    profile_id TEXT NOT NULL,
    output_key TEXT NOT NULL,
    item_id TEXT NOT NULL,
    ready_quantity INTEGER NOT NULL CHECK (ready_quantity > 0),
    PRIMARY KEY (profile_id, output_key, item_id),
    FOREIGN KEY (profile_id, output_key)
        REFERENCES companion_output_claim(profile_id, output_key)
        ON DELETE CASCADE
);

-- The migration driver replaces these tokens before executing this statement.
INSERT INTO schema_history(version, lineage, applied_at_ms, schema_hash)
VALUES (2, 'tamework-state', __APPLIED_AT_MS__, '__V2_SCHEMA_HASH__');
