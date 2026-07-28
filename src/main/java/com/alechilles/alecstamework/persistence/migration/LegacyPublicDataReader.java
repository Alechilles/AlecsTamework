package com.alechilles.alecstamework.persistence.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/** Reads the immutable row model shared by all public schema importers. */
final class LegacyPublicDataReader {
    @Nonnull
    LegacyPublicData read(@Nonnull Connection connection, int schemaVersion) throws Exception {
        if (connection == null || schemaVersion < 2 || schemaVersion > 4) {
            throw new IllegalArgumentException("Public source connection and v2-v4 version required");
        }
        return new LegacyPublicData(
                profiles(connection),
                aliases(connection),
                toolLinks(connection),
                snapshots(connection),
                coopSlots(connection, schemaVersion),
                profileStates(connection),
                schemaVersion >= 3 ? extensionData(connection) : List.of()
        );
    }

    private List<LegacyPublicData.Profile> profiles(Connection connection) throws Exception {
        ArrayList<LegacyPublicData.Profile> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT profile_id, current_npc_uuid, owner_uuid, display_name, role_id,
                            state_json, state_hash, last_world_name, created_at_ms,
                            updated_at_ms, last_active_at_ms
                     FROM npc_profiles ORDER BY profile_id
                     """)) {
            while (rows.next()) {
                result.add(new LegacyPublicData.Profile(
                        rows.getString("profile_id"),
                        rows.getString("current_npc_uuid"),
                        rows.getString("owner_uuid"),
                        rows.getString("display_name"),
                        rows.getString("role_id"),
                        rows.getString("state_json"),
                        rows.getString("state_hash"),
                        rows.getString("last_world_name"),
                        rows.getLong("created_at_ms"),
                        rows.getLong("updated_at_ms"),
                        rows.getLong("last_active_at_ms")
                ));
            }
        }
        return List.copyOf(result);
    }

    private List<LegacyPublicData.Alias> aliases(Connection connection) throws Exception {
        ArrayList<LegacyPublicData.Alias> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT npc_uuid, profile_id, is_current, mapped_at_ms
                     FROM npc_uuid_aliases
                     ORDER BY profile_id, mapped_at_ms, npc_uuid
                     """)) {
            while (rows.next()) {
                result.add(new LegacyPublicData.Alias(
                        rows.getString("npc_uuid"),
                        rows.getString("profile_id"),
                        rows.getInt("is_current"),
                        rows.getLong("mapped_at_ms")
                ));
            }
        }
        return List.copyOf(result);
    }

    private List<LegacyPublicData.ToolLink> toolLinks(Connection connection) throws Exception {
        ArrayList<LegacyPublicData.ToolLink> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms
                     FROM npc_tool_links ORDER BY profile_id, tool_uuid, link_type
                     """)) {
            while (rows.next()) {
                result.add(new LegacyPublicData.ToolLink(
                        rows.getString("profile_id"),
                        rows.getString("tool_uuid"),
                        rows.getString("link_type"),
                        rows.getLong("created_at_ms"),
                        rows.getLong("updated_at_ms")
                ));
            }
        }
        return List.copyOf(result);
    }

    private List<LegacyPublicData.Snapshot> snapshots(Connection connection) throws Exception {
        ArrayList<LegacyPublicData.Snapshot> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT snapshot_id, profile_id, snapshot_type, snapshot_version,
                            payload_json, is_active, created_at_ms
                     FROM npc_snapshots ORDER BY snapshot_id
                     """)) {
            while (rows.next()) {
                result.add(new LegacyPublicData.Snapshot(
                        rows.getLong("snapshot_id"),
                        rows.getString("profile_id"),
                        rows.getString("snapshot_type"),
                        rows.getInt("snapshot_version"),
                        rows.getString("payload_json"),
                        rows.getInt("is_active"),
                        rows.getLong("created_at_ms")
                ));
            }
        }
        return List.copyOf(result);
    }

    private List<LegacyPublicData.CoopSlot> coopSlots(Connection connection, int version)
            throws Exception {
        ArrayList<LegacyPublicData.CoopSlot> result = new ArrayList<>();
        String stateColumn = version >= 4 ? "state_snapshot_json" : "NULL AS state_snapshot_json";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT world_name, coop_id, x, y, z, resident_slot, profile_id,
                            housed_npc_uuid, last_released_npc_uuid, captured_at_ms,
                            released_at_ms, updated_at_ms, %s
                     FROM coop_slots
                     ORDER BY world_name, coop_id, x, y, z, resident_slot
                     """.formatted(stateColumn))) {
            while (rows.next()) {
                result.add(coopSlot(rows));
            }
        }
        return List.copyOf(result);
    }

    private LegacyPublicData.CoopSlot coopSlot(ResultSet rows) throws Exception {
        return new LegacyPublicData.CoopSlot(
                rows.getString("world_name"),
                rows.getString("coop_id"),
                rows.getInt("x"),
                rows.getInt("y"),
                rows.getInt("z"),
                rows.getInt("resident_slot"),
                rows.getString("profile_id"),
                rows.getString("housed_npc_uuid"),
                rows.getString("last_released_npc_uuid"),
                rows.getLong("captured_at_ms"),
                rows.getLong("released_at_ms"),
                rows.getLong("updated_at_ms"),
                rows.getString("state_snapshot_json")
        );
    }

    private List<LegacyPublicData.ProfileState> profileStates(Connection connection)
            throws Exception {
        ArrayList<LegacyPublicData.ProfileState> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT profile_id, capture_active, death_active, lost_active,
                            in_coop, coop_key, updated_at_ms
                     FROM profile_states ORDER BY profile_id
                     """)) {
            while (rows.next()) {
                result.add(new LegacyPublicData.ProfileState(
                        rows.getString("profile_id"),
                        rows.getInt("capture_active"),
                        rows.getInt("death_active"),
                        rows.getInt("lost_active"),
                        rows.getInt("in_coop"),
                        rows.getString("coop_key"),
                        rows.getLong("updated_at_ms")
                ));
            }
        }
        return List.copyOf(result);
    }

    private List<LegacyPublicData.ExtensionData> extensionData(Connection connection)
            throws Exception {
        ArrayList<LegacyPublicData.ExtensionData> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT profile_id, namespace, data_key, json_payload,
                            created_at_ms, updated_at_ms
                     FROM api_profile_data ORDER BY profile_id, namespace, data_key
                     """)) {
            while (rows.next()) {
                result.add(new LegacyPublicData.ExtensionData(
                        rows.getString("profile_id"),
                        rows.getString("namespace"),
                        rows.getString("data_key"),
                        rows.getString("json_payload"),
                        rows.getLong("created_at_ms"),
                        rows.getLong("updated_at_ms")
                ));
            }
        }
        return List.copyOf(result);
    }
}
