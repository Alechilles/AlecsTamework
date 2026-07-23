CREATE TABLE api_profile_data (
    profile_id TEXT NOT NULL,
    namespace TEXT NOT NULL,
    data_key TEXT NOT NULL,
    json_payload TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY (profile_id, namespace, data_key),
    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_api_profile_data_profile_namespace
    ON api_profile_data(profile_id, namespace);
