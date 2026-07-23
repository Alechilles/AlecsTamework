-- @include schema-public-v2.sql
-- @include schema-public-v3.sql
-- @include schema-public-v4.sql

INSERT INTO schema_migrations(version, name, applied_at_ms) VALUES
    (2, 'schema_v2', 1000),
    (3, 'schema_v3_api_profile_data', 1001),
    (4, 'schema_v4_coop_state_snapshot', 1002);

INSERT INTO npc_uuid_aliases(npc_uuid, profile_id, is_current, mapped_at_ms) VALUES (
    '02000000-0000-0000-0000-000000000001',
    '22000000-0000-0000-0000-000000000001',
    1,
    200
);
