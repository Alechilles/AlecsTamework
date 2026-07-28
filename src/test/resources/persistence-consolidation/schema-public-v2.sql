CREATE TABLE schema_migrations (
    version INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    applied_at_ms INTEGER NOT NULL
);

CREATE TABLE npc_profiles (
    profile_id TEXT PRIMARY KEY,
    current_npc_uuid TEXT UNIQUE,
    owner_uuid TEXT,
    display_name TEXT,
    role_id TEXT,
    state_json TEXT,
    state_hash TEXT,
    last_world_name TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    last_active_at_ms INTEGER NOT NULL
);

CREATE INDEX idx_npc_profiles_owner_uuid ON npc_profiles(owner_uuid);
CREATE INDEX idx_npc_profiles_current_uuid ON npc_profiles(current_npc_uuid);

CREATE TABLE npc_uuid_aliases (
    npc_uuid TEXT PRIMARY KEY,
    profile_id TEXT NOT NULL,
    is_current INTEGER NOT NULL,
    mapped_at_ms INTEGER NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_npc_uuid_aliases_profile_id ON npc_uuid_aliases(profile_id);
CREATE INDEX idx_npc_uuid_aliases_current_profile ON npc_uuid_aliases(is_current, profile_id);

CREATE TABLE npc_tool_links (
    profile_id TEXT NOT NULL,
    tool_uuid TEXT NOT NULL,
    link_type TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY (profile_id, tool_uuid, link_type),
    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_npc_tool_links_tool_uuid ON npc_tool_links(tool_uuid);
CREATE INDEX idx_npc_tool_links_profile_id ON npc_tool_links(profile_id);

CREATE TABLE npc_snapshots (
    snapshot_id INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id TEXT NOT NULL,
    snapshot_type TEXT NOT NULL,
    snapshot_version INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    is_active INTEGER NOT NULL,
    created_at_ms INTEGER NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_npc_snapshots_profile_type_active
    ON npc_snapshots(profile_id, snapshot_type, is_active);
CREATE INDEX idx_npc_snapshots_type_active_created
    ON npc_snapshots(snapshot_type, is_active, created_at_ms);

CREATE TABLE coop_slots (
    world_name TEXT NOT NULL,
    coop_id TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    resident_slot INTEGER NOT NULL,
    profile_id TEXT,
    housed_npc_uuid TEXT,
    last_released_npc_uuid TEXT,
    captured_at_ms INTEGER NOT NULL,
    released_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY (world_name, coop_id, x, y, z, resident_slot),
    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE SET NULL
);

CREATE INDEX idx_coop_slots_profile_id ON coop_slots(profile_id);
CREATE INDEX idx_coop_slots_last_released_npc_uuid ON coop_slots(last_released_npc_uuid);
CREATE INDEX idx_coop_slots_world_coop ON coop_slots(world_name, coop_id);

CREATE TABLE profile_states (
    profile_id TEXT PRIMARY KEY,
    capture_active INTEGER NOT NULL,
    death_active INTEGER NOT NULL,
    lost_active INTEGER NOT NULL,
    in_coop INTEGER NOT NULL,
    coop_key TEXT,
    updated_at_ms INTEGER NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES npc_profiles(profile_id) ON DELETE CASCADE
);
