ALTER TABLE bonded_companion_operation RENAME TO bonded_v7_operation;

DROP INDEX bonded_operation_retention_idx;

CREATE TABLE bonded_companion_operation (
    caller_namespace TEXT NOT NULL CHECK (length(trim(caller_namespace)) > 0),
    idempotency_key TEXT NOT NULL CHECK (length(trim(idempotency_key)) > 0),
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
    profile_id TEXT,
    operation_type TEXT NOT NULL CHECK (
        operation_type IN ('CAPTURE', 'PROVISION', 'STORE', 'REVIVE')
    ),
    request_hash TEXT NOT NULL CHECK (
        length(request_hash) = 64
        AND lower(request_hash) = request_hash
        AND request_hash NOT GLOB '*[^0-9a-f]*'
    ),
    operation_state TEXT NOT NULL CHECK (
        operation_state IN ('SUCCEEDED', 'REJECTED')
    ),
    result_json TEXT NOT NULL CHECK (
        json_valid(result_json) AND json_type(result_json) = 'object'
    ),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL CHECK (expires_at_ms <> 0),
    expected_revision INTEGER CHECK (
        expected_revision IS NULL OR expected_revision >= 0
    ),
    PRIMARY KEY(caller_namespace, idempotency_key)
);

INSERT INTO bonded_companion_operation(
    caller_namespace, idempotency_key, owner_uuid, roster_id, profile_id,
    operation_type, request_hash, operation_state, result_json,
    created_at_ms, updated_at_ms, expires_at_ms, expected_revision
)
SELECT
    caller_namespace, idempotency_key, owner_uuid, roster_id, profile_id,
    operation_type, request_hash, operation_state, result_json,
    created_at_ms, updated_at_ms, expires_at_ms, expected_revision
FROM bonded_v7_operation
WHERE operation_type IN ('CAPTURE', 'PROVISION', 'STORE', 'REVIVE')
  AND operation_state IN ('SUCCEEDED', 'REJECTED')
  AND result_json IS NOT NULL;

DROP TABLE bonded_v7_operation;

CREATE INDEX bonded_operation_retention_idx
    ON bonded_companion_operation(
        expires_at_ms, caller_namespace, idempotency_key
    );

ALTER TABLE bonded_schema_history RENAME TO bonded_v7_schema_history;

CREATE TABLE bonded_schema_history (
    version INTEGER PRIMARY KEY CHECK (version BETWEEN 1 AND 8),
    lineage TEXT NOT NULL CHECK (lineage = 'bonded-companions'),
    applied_at_ms INTEGER NOT NULL,
    schema_hash TEXT NOT NULL CHECK (
        length(schema_hash) = 64
        AND lower(schema_hash) = schema_hash
        AND schema_hash NOT GLOB '*[^0-9a-f]*'
    )
);

INSERT INTO bonded_schema_history
SELECT version, lineage, applied_at_ms, schema_hash
FROM bonded_v7_schema_history;

DROP TABLE bonded_v7_schema_history;
