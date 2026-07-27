CREATE TABLE bonded_companion_lease_admission (
    admission_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id TEXT NOT NULL CHECK (length(trim(profile_id)) > 0),
    lease_token TEXT NOT NULL CHECK (length(trim(lease_token)) > 0),
    admitted_at_ms INTEGER NOT NULL,
    UNIQUE(profile_id, lease_token)
);

INSERT INTO bonded_companion_lease_admission(
    profile_id, lease_token, admitted_at_ms
)
SELECT profile_id, lease_token, started_at_ms
FROM bonded_companion_lease;

CREATE INDEX bonded_lease_admission_identity_idx
ON bonded_companion_lease_admission(profile_id, lease_token);

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
