CREATE TABLE schema_migrations (
    version INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    applied_at_ms INTEGER NOT NULL
);

INSERT INTO schema_migrations(version, name, applied_at_ms) VALUES
    (2, 'schema_v2', 1000),
    (3, 'schema_v3_api_profile_data', 1001),
    (4, 'schema_v4_coop_state_snapshot', 1002),
    (5, 'schema_v5_managed_coop', 1003);

CREATE TABLE managed_coop_authority (
    authority_key TEXT PRIMARY KEY,
    generation INTEGER NOT NULL
);
