UPDATE bonded_companion_operation AS current
SET result_json = (
    SELECT json_object(
        'code', CASE legacy.operation_state
            WHEN 'SUCCEEDED' THEN 'APPLIED'
            WHEN 'FAILED' THEN 'STORAGE_FAILURE'
            ELSE 'CONFLICT'
        END,
        'reason', 'legacy-v1-operation',
        'valueType', CASE legacy.operation_type
            WHEN 'SUMMON' THEN 'LEASE'
            WHEN 'CLEANUP' THEN 'CLEANUP'
            ELSE 'PROFILE'
        END,
        'value', CASE
            WHEN legacy.operation_state <> 'SUCCEEDED' THEN NULL
            WHEN legacy.operation_type IN (
                'CAPTURE', 'PROVISION', 'STORE', 'REVIVE'
            ) THEN printf(
                '%s',
                json_set(
                    legacy.result_json,
                    '$.snapshotJson',
                    printf(
                        '{"encoding":"hex-utf8","payload":"%s"}',
                        hex(CAST(json_extract(
                            legacy.result_json, '$.snapshotJson'
                        ) AS BLOB))
                    )
                )
            )
            ELSE legacy.result_json
        END,
        'legacyResult', legacy.result_json
    )
    FROM temp.bonded_v1_terminal_operation legacy
    WHERE legacy.caller_namespace = current.caller_namespace
      AND legacy.idempotency_key = current.idempotency_key
)
WHERE EXISTS (
    SELECT 1
    FROM temp.bonded_v1_terminal_operation legacy
    WHERE legacy.caller_namespace = current.caller_namespace
      AND legacy.idempotency_key = current.idempotency_key
);

ALTER TABLE bonded_schema_history RENAME TO bonded_v2_schema_history;

CREATE TABLE bonded_schema_history (
    version INTEGER PRIMARY KEY CHECK (version BETWEEN 1 AND 3),
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
FROM bonded_v2_schema_history;

DROP TABLE bonded_v2_schema_history;
