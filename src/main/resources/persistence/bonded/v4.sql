ALTER TABLE bonded_companion_cleanup
ADD COLUMN world_key TEXT NOT NULL
    DEFAULT '__legacy_unknown_world__'
    CHECK (length(trim(world_key)) > 0);

UPDATE bonded_companion_operation
SET result_json = json_set(
    result_json,
    '$.value.worldKey',
    '__legacy_unknown_world__'
)
WHERE operation_state <> 'PENDING'
  AND json_extract(result_json, '$.valueType') = 'CLEANUP'
  AND json_type(result_json, '$.value') = 'object'
  AND json_type(result_json, '$.value.worldKey') IS NULL;

ALTER TABLE bonded_schema_history RENAME TO bonded_v3_schema_history;

CREATE TABLE bonded_schema_history (
    version INTEGER PRIMARY KEY CHECK (version BETWEEN 1 AND 4),
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
FROM bonded_v3_schema_history;

DROP TABLE bonded_v3_schema_history;
