ALTER TABLE bonded_companion_operation
ADD COLUMN expected_revision INTEGER
    CHECK (expected_revision IS NULL OR expected_revision >= 0);

UPDATE bonded_companion_operation
SET expires_at_ms = 9223372036854775807
WHERE operation_type = 'REVIVE'
  AND operation_state IN ('SUCCEEDED', 'REJECTED')
  AND result_json IS NOT NULL;

ALTER TABLE bonded_schema_history RENAME TO bonded_v4_schema_history;

CREATE TABLE bonded_schema_history (
    version INTEGER PRIMARY KEY CHECK (version BETWEEN 1 AND 5),
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
FROM bonded_v4_schema_history;

DROP TABLE bonded_v4_schema_history;
