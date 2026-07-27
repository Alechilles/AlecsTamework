CREATE TABLE bonded_companion_capture_source (
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
    source_npc_uuid TEXT NOT NULL CHECK (
        length(source_npc_uuid) = 36
        AND substr(source_npc_uuid, 9, 1) = '-'
        AND substr(source_npc_uuid, 14, 1) = '-'
        AND substr(source_npc_uuid, 19, 1) = '-'
        AND substr(source_npc_uuid, 24, 1) = '-'
        AND lower(source_npc_uuid) = source_npc_uuid
        AND replace(source_npc_uuid, '-', '') NOT GLOB '*[^0-9a-f]*'
    ),
    source_world_key TEXT NOT NULL CHECK (length(trim(source_world_key)) > 0),
    caller_namespace TEXT NOT NULL CHECK (length(trim(caller_namespace)) > 0),
    idempotency_key TEXT NOT NULL CHECK (length(trim(idempotency_key)) > 0),
    request_hash TEXT NOT NULL CHECK (
        length(request_hash) = 64
        AND lower(request_hash) = request_hash
        AND request_hash NOT GLOB '*[^0-9a-f]*'
    ),
    capture_evidence_json TEXT NOT NULL CHECK (
        json_valid(capture_evidence_json)
        AND json_type(capture_evidence_json) = 'object'
    ),
    capture_snapshot_json TEXT NOT NULL CHECK (
        json_valid(capture_snapshot_json)
        AND json_type(capture_snapshot_json) = 'object'
    ),
    committed_at_ms INTEGER NOT NULL,
    event_published_at_ms INTEGER,
    UNIQUE(caller_namespace, idempotency_key),
    FOREIGN KEY(profile_id, owner_uuid, roster_id)
        REFERENCES bonded_companion_profile(profile_id, owner_uuid, roster_id)
        ON DELETE CASCADE
);

INSERT INTO bonded_companion_capture_source(
    profile_id, owner_uuid, roster_id, source_npc_uuid, source_world_key,
    caller_namespace, idempotency_key, request_hash,
    capture_evidence_json, capture_snapshot_json, committed_at_ms,
    event_published_at_ms
)
SELECT
    json_extract(operation.result_json, '$.captureEvidence.profileId'),
    operation.owner_uuid,
    operation.roster_id,
    json_extract(operation.result_json, '$.captureEvidence.sourceNpcUuid'),
    json_extract(operation.result_json, '$.captureEvidence.sourceWorldKey'),
    operation.caller_namespace,
    operation.idempotency_key,
    operation.request_hash,
    json_extract(operation.result_json, '$.captureEvidence'),
    json_extract(operation.result_json, '$.value'),
    json_extract(operation.result_json, '$.captureEvidence.committedAtMs'),
    json_extract(operation.result_json, '$.captureEventPublishedAtMs')
FROM bonded_companion_operation operation
JOIN bonded_companion_profile profile
  ON profile.profile_id = json_extract(
      operation.result_json, '$.captureEvidence.profileId'
  )
 AND profile.owner_uuid = operation.owner_uuid
 AND profile.roster_id = operation.roster_id
WHERE operation.operation_type = 'CAPTURE'
  AND operation.operation_state = 'SUCCEEDED'
  AND json_type(operation.result_json, '$.captureEvidence') = 'object'
  AND json_type(operation.result_json, '$.value') = 'text';

CREATE UNIQUE INDEX bonded_capture_source_uuid_uq
ON bonded_companion_capture_source(source_npc_uuid);

DROP INDEX bonded_capture_source_once_idx;

ALTER TABLE bonded_schema_history RENAME TO bonded_v6_schema_history;

CREATE TABLE bonded_schema_history (
    version INTEGER PRIMARY KEY CHECK (version BETWEEN 1 AND 7),
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
FROM bonded_v6_schema_history;

DROP TABLE bonded_v6_schema_history;
