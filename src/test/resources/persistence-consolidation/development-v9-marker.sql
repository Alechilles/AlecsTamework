CREATE TABLE schema_migrations (
    version INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    applied_at_ms INTEGER NOT NULL
);

INSERT INTO schema_migrations(version, name, applied_at_ms) VALUES
    (2, 'schema_v2', 1000),
    (3, 'schema_v3_api_profile_data', 1001),
    (4, 'schema_v4_coop_state_snapshot', 1002),
    (5, 'schema_v5_managed_coop', 1003),
    (6, 'schema_v6_population', 1004),
    (7, 'schema_v7_resilience', 1005),
    (8, 'schema_v8_feature_operations', 1006),
    (9, 'schema_v9_command_family_authorities', 1007);

CREATE TABLE command_timed_summon_sessions (
    session_id TEXT PRIMARY KEY,
    profile_id TEXT NOT NULL
);
