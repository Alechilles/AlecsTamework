CREATE UNIQUE INDEX bonded_capture_source_once_idx
ON bonded_companion_operation(
    json_extract(result_json, '$.captureEvidence.sourceNpcUuid')
)
WHERE operation_type = 'CAPTURE'
  AND operation_state = 'SUCCEEDED'
  AND json_type(
      result_json, '$.captureEvidence.sourceNpcUuid'
  ) = 'text';

ALTER TABLE bonded_schema_history RENAME TO bonded_v5_schema_history;

CREATE TABLE bonded_schema_history (
    version INTEGER PRIMARY KEY CHECK (version BETWEEN 1 AND 6),
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
FROM bonded_v5_schema_history;

DROP TABLE bonded_v5_schema_history;
